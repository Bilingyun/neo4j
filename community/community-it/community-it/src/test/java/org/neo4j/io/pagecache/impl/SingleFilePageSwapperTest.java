/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.OpenOption;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.adversaries.RandomAdversary;
import org.neo4j.adversaries.fs.AdversarialFileSystemAbstraction;
import org.neo4j.internal.unsafe.UnsafeUtil;
import org.neo4j.io.IOUtils;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.DelegatingFileSystemAbstraction;
import org.neo4j.io.fs.DelegatingStoreChannel;
import org.neo4j.io.fs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.fs.StoreFileChannel;
import org.neo4j.io.memory.ByteBuffers;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.PageSwapperFactory;
import org.neo4j.io.pagecache.PageSwapperTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.test.matchers.ByteArrayMatcher.byteArray;
import static org.neo4j.test.proc.ProcessUtil.getClassPath;
import static org.neo4j.test.proc.ProcessUtil.getJavaExecutable;

public class SingleFilePageSwapperTest extends PageSwapperTest
{
    private EphemeralFileSystemAbstraction ephemeralFileSystem;
    private DefaultFileSystemAbstraction fileSystem;
    private File file;

    @BeforeEach
    void setUp() throws IOException
    {
        file = new File( "file" ).getCanonicalFile();
        ephemeralFileSystem = new EphemeralFileSystemAbstraction();
        fileSystem = new DefaultFileSystemAbstraction();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        IOUtils.closeAll( ephemeralFileSystem, fileSystem );
    }

    @Override
    protected PageSwapperFactory swapperFactory()
    {
        SingleFilePageSwapperFactory factory = new SingleFilePageSwapperFactory();
        factory.open( getFs() );
        return factory;
    }

    @Override
    protected void mkdirs( File dir ) throws IOException
    {
        getFs().mkdirs( dir );
    }

    protected File getFile()
    {
        return file;
    }

    protected FileSystemAbstraction getFs()
    {
        return getEphemeralFileSystem();
    }

    private FileSystemAbstraction getEphemeralFileSystem()
    {
        return ephemeralFileSystem;
    }

    FileSystemAbstraction getRealFileSystem()
    {
        return fileSystem;
    }

    private void putBytes( long page, byte[] data, int srcOffset, int tgtOffset, int length )
    {
        for ( int i = 0; i < length; i++ )
        {
            UnsafeUtil.putByte( page + srcOffset + i, data[tgtOffset + i] );
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void swappingInMustFillPageWithData( int noChannelStriping ) throws Exception
    {
        byte[] bytes = new byte[] { 1, 2, 3, 4 };
        StoreChannel channel = getFs().write( getFile() );
        channel.writeAll( wrap( bytes ) );
        channel.close();

        PageSwapperFactory factory = createSwapperFactory();
        PageSwapper swapper = createSwapper( factory, getFile(), 4, null, false, bool( noChannelStriping ) );
        long target = createPage( 4 );
        swapper.read( 0, target );

        assertThat( array( target ), byteArray( bytes ) );
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void mustZeroFillPageBeyondEndOfFile( int noChannelStriping ) throws Exception
    {
        byte[] bytes = new byte[] {
                // --- page 0:
                1, 2, 3, 4,
                // --- page 1:
                5, 6
        };
        StoreChannel channel = getFs().write( getFile() );
        channel.writeAll( wrap( bytes ) );
        channel.close();

        PageSwapperFactory factory = createSwapperFactory();
        PageSwapper swapper = createSwapper( factory, getFile(), 4, null, false, bool( noChannelStriping ) );
        long target = createPage( 4 );
        swapper.read( 1, target );

        assertThat( array( target ), byteArray( new byte[]{5, 6, 0, 0} ) );
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void swappingOutMustWritePageToFile( int noChannelStriping ) throws Exception
    {
        getFs().write( getFile() ).close();

        byte[] expected = new byte[] { 1, 2, 3, 4 };
        long page = createPage( expected );

        PageSwapperFactory factory = createSwapperFactory();
        PageSwapper swapper = createSwapper( factory, getFile(), 4, null, false, bool( noChannelStriping ) );
        swapper.write( 0, page );

        InputStream stream = getFs().openAsInputStream( getFile() );
        byte[] actual = new byte[expected.length];

        assertThat( stream.read( actual ), is( actual.length ) );
        assertThat( actual, byteArray( expected ) );
    }

    private long createPage( byte[] expected )
    {
        long page = createPage( expected.length );
        putBytes( page, expected, 0, 0, expected.length );
        return page;
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void swappingOutMustNotOverwriteDataBeyondPage( int noChannelStriping ) throws Exception
    {
        byte[] initialData = new byte[] {
                // --- page 0:
                1, 2, 3, 4,
                // --- page 1:
                5, 6, 7, 8,
                // --- page 2:
                9, 10
        };
        byte[] finalData = new byte[] {
                // --- page 0:
                1, 2, 3, 4,
                // --- page 1:
                8, 7, 6, 5,
                // --- page 2:
                9, 10
        };
        StoreChannel channel = getFs().write( getFile() );
        channel.writeAll( wrap( initialData ) );
        channel.close();

        byte[] change = new byte[] { 8, 7, 6, 5 };
        long page = createPage( change );

        PageSwapperFactory factory = createSwapperFactory();
        PageSwapper swapper = createSwapper( factory, getFile(), 4, null, false, bool( noChannelStriping ) );
        swapper.write( 1, page );

        InputStream stream = getFs().openAsInputStream( getFile() );
        byte[] actual = new byte[(int) getFs().getFileSize( getFile() )];

        assertThat( stream.read( actual ), is( actual.length ) );
        assertThat( actual, byteArray( finalData ) );
    }

    /**
     * The OverlappingFileLockException is thrown when tryLock is called on the same file *in the same JVM*.
     */
    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void creatingSwapperForFileMustTakeLockOnFile( int noChannelStriping ) throws Exception
    {
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( fileSystem );
        File file = testDir.file( "file" );
        fileSystem.write( file ).close();

        PageSwapper pageSwapper = createSwapper( factory, file, 4, NO_CALLBACK, false, bool( noChannelStriping ) );

        try
        {
            StoreChannel channel = fileSystem.write( file );
            assertThrows( OverlappingFileLockException.class, channel::tryLock );
        }
        finally
        {
            pageSwapper.close();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void creatingSwapperForInternallyLockedFileMustThrow( int noChannelStriping ) throws Exception
    {
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( fileSystem );
        File file = testDir.file( "file" );

        StoreFileChannel channel = fileSystem.write( file );

        try ( FileLock fileLock = channel.tryLock() )
        {
            assertThat( fileLock, is( not( nullValue() ) ) );
            assertThrows( FileLockException.class, () -> createSwapper( factory, file, 4, NO_CALLBACK, true, bool( noChannelStriping ) ) );
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void creatingSwapperForExternallyLockedFileMustThrow( int noChannelStriping ) throws Exception
    {
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( fileSystem );
        File file = testDir.file( "file" );

        fileSystem.write( file ).close();

        ProcessBuilder pb = new ProcessBuilder(
                getJavaExecutable().toString(),
                "-cp", getClassPath(),
                LockThisFileProgram.class.getCanonicalName(), file.getAbsolutePath() );
        File wd = new File( "target/test-classes" ).getAbsoluteFile();
        pb.directory( wd );
        Process process = pb.start();
        BufferedReader stdout = new BufferedReader( new InputStreamReader( process.getInputStream() ) );
        InputStream stderr = process.getErrorStream();
        try
        {
            assumeTrue( LockThisFileProgram.LOCKED_OUTPUT.equals( stdout.readLine() ) );
        }
        catch ( Throwable e )
        {
            int b = stderr.read();
            while ( b != -1 )
            {
                System.err.write( b );
                b = stderr.read();
            }
            System.err.flush();
            int exitCode = process.waitFor();
            System.out.println( "exitCode = " + exitCode );
            throw e;
        }

        try
        {
            assertThrows( FileLockException.class, () -> createSwapper( factory, file, 4, NO_CALLBACK, true, bool( noChannelStriping ) ) );
        }
        finally
        {
            process.getOutputStream().write( 0 );
            process.getOutputStream().flush();
            process.waitFor();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void mustUnlockFileWhenThePageSwapperIsClosed( int noChannelStriping ) throws Exception
    {
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( fileSystem );
        File file = testDir.file( "file" );
        fileSystem.write( file ).close();

        createSwapper( factory, file, 4, NO_CALLBACK, false, bool( noChannelStriping ) ).close();

        try ( StoreFileChannel channel = fileSystem.write( file );
              FileLock fileLock = channel.tryLock() )
        {
            assertThat( fileLock, is( not( nullValue() ) ) );
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void fileMustRemainLockedEvenIfChannelIsClosedByStrayInterrupt( int noChannelStriping ) throws Exception
    {
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( fileSystem );
        File file = testDir.file( "file" );
        fileSystem.write( file ).close();

        PageSwapper pageSwapper = createSwapper( factory, file, 4, NO_CALLBACK, false, bool( noChannelStriping ) );

        try
        {
            StoreChannel channel = fileSystem.write( file );

            Thread.currentThread().interrupt();
            pageSwapper.force();

            assertThrows( OverlappingFileLockException.class, channel::tryLock );
        }
        finally
        {
            pageSwapper.close();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    @DisabledOnOs( OS.WINDOWS )
    void mustCloseFilesIfTakingFileLockThrows( int noChannelStriping ) throws Exception
    {
        final AtomicInteger openFilesCounter = new AtomicInteger();
        PageSwapperFactory factory = createSwapperFactory();
        factory.open( new DelegatingFileSystemAbstraction( fileSystem )
        {
            @Override
            public StoreChannel open( File fileName, Set<OpenOption> options ) throws IOException
            {
                openFilesCounter.getAndIncrement();
                return new DelegatingStoreChannel( super.open( fileName, options ) )
                {
                    @Override
                    public void close() throws IOException
                    {
                        openFilesCounter.getAndDecrement();
                        super.close();
                    }
                };
            }
        } );
        File file = testDir.file( "file" );
        try ( StoreChannel ch = fileSystem.write( file );
                FileLock ignore = ch.tryLock() )
        {
            createSwapper( factory, file, 4, NO_CALLBACK, false, bool( noChannelStriping ) ).close();
            fail( "Creating a page swapper for a locked channel should have thrown" );
        }
        catch ( FileLockException e )
        {
            // As expected.
        }
        assertThat( openFilesCounter.get(), is( 0 ) );
    }

    private byte[] array( long page )
    {
        int size = sizeOfAsInt( page );
        byte[] array = new byte[size];
        for ( int i = 0; i < size; i++ )
        {
            array[i] = UnsafeUtil.getByte( page + i );
        }
        return array;
    }

    private ByteBuffer wrap( byte[] bytes )
    {
        ByteBuffer buffer = ByteBuffers.allocate( bytes.length );
        for ( byte b : bytes )
        {
            buffer.put( b );
        }
        buffer.clear();
        return buffer;
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void mustHandleMischiefInPositionedRead( int noChannelStriping ) throws Exception
    {
        int bytesTotal = 512;
        byte[] data = new byte[bytesTotal];
        ThreadLocalRandom.current().nextBytes( data );

        PageSwapperFactory factory = createSwapperFactory();
        factory.open( getFs() );
        File file = getFile();
        PageSwapper swapper = createSwapper( factory, file, bytesTotal, NO_CALLBACK, true, bool( noChannelStriping ) );
        try
        {
            long page = createPage( data );
            swapper.write( 0, page );
        }
        finally
        {
            swapper.close();
        }

        RandomAdversary adversary = new RandomAdversary( 0.5, 0.0, 0.0 );
        factory.open( new AdversarialFileSystemAbstraction( adversary, getFs() ) );
        swapper = createSwapper( factory, file, bytesTotal, NO_CALLBACK, false, bool( noChannelStriping ) );

        long page = createPage( bytesTotal );

        try
        {
            for ( int i = 0; i < 10_000; i++ )
            {
                clear( page );
                assertThat( swapper.read( 0, page ), is( (long) bytesTotal ) );
                assertThat( array( page ), is( data ) );
            }
        }
        finally
        {
            swapper.close();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void mustHandleMischiefInPositionedWrite( int noChannelStriping ) throws Exception
    {
        int bytesTotal = 512;
        byte[] data = new byte[bytesTotal];
        ThreadLocalRandom.current().nextBytes( data );
        long zeroPage = createPage( bytesTotal );
        clear( zeroPage );

        File file = getFile();
        PageSwapperFactory factory = createSwapperFactory();
        RandomAdversary adversary = new RandomAdversary( 0.5, 0.0, 0.0 );
        factory.open( new AdversarialFileSystemAbstraction( adversary, getFs() ) );
        PageSwapper swapper = createSwapper( factory, file, bytesTotal, NO_CALLBACK, true, bool( noChannelStriping ) );

        long page = createPage( bytesTotal );

        try
        {
            for ( int i = 0; i < 10_000; i++ )
            {
                adversary.setProbabilityFactor( 0 );
                swapper.write( 0, zeroPage );
                putBytes( page, data, 0, 0, data.length );
                adversary.setProbabilityFactor( 1 );
                assertThat( swapper.write( 0, page ), is( (long) bytesTotal ) );
                clear( page );
                adversary.setProbabilityFactor( 0 );
                swapper.read( 0, page );
                assertThat( array( page ), is( data ) );
            }
        }
        finally
        {
            swapper.close();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void mustHandleMischiefInPositionedVectoredRead( int noChannelStriping ) throws Exception
    {
        int bytesTotal = 512;
        int bytesPerPage = 32;
        int pageCount = bytesTotal / bytesPerPage;
        byte[] data = new byte[bytesTotal];
        ThreadLocalRandom.current().nextBytes( data );

        PageSwapperFactory factory = createSwapperFactory();
        factory.open( getFs() );
        File file = getFile();
        PageSwapper swapper = createSwapper( factory, file, bytesTotal, NO_CALLBACK, true, bool( noChannelStriping ) );
        try
        {
            long page = createPage( data );
            swapper.write( 0, page );
        }
        finally
        {
            swapper.close();
        }

        RandomAdversary adversary = new RandomAdversary( 0.5, 0.0, 0.0 );
        factory.open( new AdversarialFileSystemAbstraction( adversary, getFs() ) );
        swapper = createSwapper( factory, file, bytesPerPage, NO_CALLBACK, false, bool( noChannelStriping ) );

        long[] pages = new long[pageCount];
        for ( int i = 0; i < pageCount; i++ )
        {
            pages[i] = createPage( bytesPerPage );
        }

        byte[] temp = new byte[bytesPerPage];
        try
        {
            for ( int i = 0; i < 10_000; i++ )
            {
                for ( long page : pages )
                {
                    clear( page );
                }
                assertThat( swapper.read( 0, pages, 0, pages.length ), is( (long) bytesTotal ) );
                for ( int j = 0; j < pageCount; j++ )
                {
                    System.arraycopy( data, j * bytesPerPage, temp, 0, bytesPerPage );
                    assertThat( array( pages[j] ), is( temp ) );
                }
            }
        }
        finally
        {
            swapper.close();
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {0, 1} )
    void mustHandleMischiefInPositionedVectoredWrite( int noChannelStriping ) throws Exception
    {
        int bytesTotal = 512;
        int bytesPerPage = 32;
        int pageCount = bytesTotal / bytesPerPage;
        byte[] data = new byte[bytesTotal];
        ThreadLocalRandom.current().nextBytes( data );
        long zeroPage = createPage( bytesPerPage );
        clear( zeroPage );

        File file = getFile();
        PageSwapperFactory factory = createSwapperFactory();
        RandomAdversary adversary = new RandomAdversary( 0.5, 0.0, 0.0 );
        factory.open( new AdversarialFileSystemAbstraction( adversary, getFs() ) );
        PageSwapper swapper = createSwapper( factory, file, bytesPerPage, NO_CALLBACK, true, bool( noChannelStriping ) );

        long[] writePages = new long[pageCount];
        long[] readPages = new long[pageCount];
        long[] zeroPages = new long[pageCount];
        for ( int i = 0; i < pageCount; i++ )
        {
            writePages[i] = createPage( bytesPerPage );
            putBytes( writePages[i], data, 0, i * bytesPerPage, bytesPerPage );
            readPages[i] = createPage( bytesPerPage );
            zeroPages[i] = zeroPage;
        }

        try
        {
            for ( int i = 0; i < 10_000; i++ )
            {
                adversary.setProbabilityFactor( 0 );
                swapper.write( 0, zeroPages, 0, pageCount );
                adversary.setProbabilityFactor( 1 );
                swapper.write( 0, writePages, 0, pageCount );
                for ( long readPage : readPages )
                {
                    clear( readPage );
                }
                adversary.setProbabilityFactor( 0 );
                assertThat( swapper.read( 0, readPages, 0, pageCount ), is( (long) bytesTotal ) );
                for ( int j = 0; j < pageCount; j++ )
                {
                    assertThat( array( readPages[j] ), is( array( writePages[j] ) ) );
                }
            }
        }
        finally
        {
            swapper.close();
        }
    }

    @Test
    void mustDisableStripingIfToldTo() throws IOException
    {
        // given
        int bytesPerPage = 32;
        PageSwapperFactory factory = createSwapperFactory();
        FileSystemAbstraction fs = mock( FileSystemAbstraction.class );
        StoreChannel channel = mock( StoreChannel.class );
        when( channel.tryLock() ).thenReturn( mock( FileLock.class ) );
        when( fs.write( any( File.class ) ) ).thenReturn( channel ).thenReturn( channel );

        // when
        factory.open( fs );
        PageSwapper swapper = createSwapper( factory, file, bytesPerPage, NO_CALLBACK, true, true );
        try
        {
            // then
            verify( fs, times(2) ).write( eq( file ) );
        }
        finally
        {
            swapper.close();
        }
    }

    /*
     * Funny how @{@link ParameterizedTest} doesn't have support for booleans so this test is using int instead, acting as boolean.
     * Good ol' C-style.
     */
    private boolean bool( int noChannelStriping )
    {
        return noChannelStriping == 1;
    }
}

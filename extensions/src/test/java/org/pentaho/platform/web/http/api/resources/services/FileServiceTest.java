/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 - 2026 by Pentaho Canada Inc. : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2030-06-15
 ******************************************************************************/



package org.pentaho.platform.web.http.api.resources.services;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pentaho.platform.api.util.LogUtil;
import org.pentaho.platform.api.engine.IPentahoObjectFactory;
import org.pentaho.platform.api.engine.IPentahoObjectReference;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.ISystemConfig;
import org.pentaho.platform.api.engine.ISystemSettings;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.api.engine.PentahoAccessControlException;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.webservices.RepositoryFileDto;
import org.mockito.MockedStatic;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.services.importer.IPlatformImporter;
import org.pentaho.platform.plugin.services.importer.PlatformImportException;
import org.pentaho.platform.plugin.services.importexport.IRepositoryImportLogger;
import org.pentaho.platform.plugin.services.importexport.SessionExpirationDetector;
import org.pentaho.platform.plugin.services.importexport.BaseExportProcessor;
import org.pentaho.platform.plugin.services.importexport.ExportHandler;
import org.pentaho.platform.repository2.ClientRepositoryPaths;
import org.pentaho.platform.repository2.unified.fileio.RepositoryFileOutputStream;
import org.pentaho.platform.repository2.unified.webservices.DefaultUnifiedRepositoryWebService;
import org.pentaho.platform.security.policy.rolebased.actions.RepositoryCreateAction;
import org.pentaho.platform.web.http.messages.Messages;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.IllegalSelectorException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import jakarta.ws.rs.core.StreamingOutput;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileServiceTest {

  private static final String PATH_CONTROL_CHARACTER = "Create Control Character \u0017 File.xml";
  private static final String PATH_SPECIAL_CHARACTERS = "éÉèÈçÇºªüÜ@£§";
  private static final String PATH_JAPANESE_CHARACTERS = "キャラクター";
  public static final String UTF_8 = StandardCharsets.UTF_8.name();

  private FileService fileService;

  @BeforeClass
  public static void init() {
    PentahoSystem.init();
  }

  @Before
  public void setUp() throws Exception {
    fileService = spy( FileService.class );
    final ISystemSettings settingsService = mock( ISystemSettings.class );
    when( settingsService.getSystemSetting( eq( "anonymous-authentication/anonymous-user" ), eq( "anonymousUser" ) ) ).thenReturn( "anonymousUser" );
    PentahoSystem.setSystemSettingsService( settingsService );
    final ISystemConfig systemConfig = mock( ISystemConfig.class );

    final IPentahoObjectFactory objectFactory = mock( IPentahoObjectFactory.class );
    when( objectFactory.objectDefined( eq( ISystemConfig.class ) ) ).thenReturn( true );
    final IPentahoObjectReference pentahoObjectReference = mock( IPentahoObjectReference.class );
    when( pentahoObjectReference.getObject() ).thenReturn( systemConfig );
    try {
      when( objectFactory.getObjectReferences( eq( ISystemConfig.class ), nullable( IPentahoSession.class ),
          nullable( Map.class ) ) ).thenReturn( new LinkedList() { {
              add( pentahoObjectReference );
            } } );
    } catch ( ObjectFactoryException e ) {
      e.printStackTrace();
    }
    when( settingsService.getSystemSetting( nullable( String.class ), nullable( String.class ) ) ).thenReturn( "" );
    PentahoSystem.registerObjectFactory( objectFactory );

    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( List.of( '/', '\\', '\t', '\r', '\n' ) ).when( repoWs ).getReservedChars();
    doReturn( repoWs ).when( fileService ).getRepoWs();
  }

  @Test( expected = FileService.InvalidNameException.class )
  public void testCreateFile_Forbidden_ControlCharactersFound() throws Exception {
    InputStream inputStream = mock( InputStream.class );

    fileService.createFile( UTF_8, PATH_CONTROL_CHARACTER, inputStream );
    verify( fileService, times( 0 ) ).idToPath( PATH_CONTROL_CHARACTER );
  }

  @Test
  public void testCreateFile_Special_Characters() throws Exception {
    InputStream inputStream = mock( InputStream.class );
    RepositoryFileOutputStream repositoryFileOutputStream = mock( RepositoryFileOutputStream.class );

    doReturn( repositoryFileOutputStream ).when( fileService ).getRepositoryFileOutputStream( nullable( String.class ) );
    doReturn( 1 ).when( fileService ).copy( inputStream, repositoryFileOutputStream );

    fileService.createFile( UTF_8, PATH_SPECIAL_CHARACTERS, inputStream );
    verify( inputStream ).close();
  }

  @Test
  public void testCreateFile_Japanese_Characters() throws Exception {
    InputStream inputStream = mock( InputStream.class );
    RepositoryFileOutputStream repositoryFileOutputStream = mock( RepositoryFileOutputStream.class );

    doReturn( repositoryFileOutputStream ).when( fileService ).getRepositoryFileOutputStream( nullable( String.class ) );
    doReturn( 1 ).when( fileService ).copy( inputStream, repositoryFileOutputStream );

    fileService.createFile( UTF_8, PATH_JAPANESE_CHARACTERS, inputStream );
    verify( inputStream ).close();
  }

  @Test( expected = FileService.InvalidNameException.class )
  public void testDoCreateDirSafe_ControlCharactersFound() throws Exception {
    fileService.doCreateDirSafe( PATH_CONTROL_CHARACTER );
  }

  @Test
  public void testDoCreateDirSafe_Special_Characters() throws Exception {
    doReturn( true ).when( fileService ).doCreateDirFor( nullable( String.class ) );

    assertTrue( fileService.doCreateDirSafe( PATH_SPECIAL_CHARACTERS ) );
  }

  @Test
  public void testDoCreateDirSafe_Japanese_Characters() throws Exception {
    doReturn( true ).when( fileService ).doCreateDirFor( nullable( String.class ) );

    assertTrue( fileService.doCreateDirSafe( PATH_JAPANESE_CHARACTERS ) );
  }

  // region doGetFileOrDirAsDownload

  private void setupRepositoryMock( String path, RepositoryFile file ) {
    org.pentaho.platform.api.repository2.unified.IUnifiedRepository mockRepo =
      mock( org.pentaho.platform.api.repository2.unified.IUnifiedRepository.class );
    when( mockRepo.getFile( path ) ).thenReturn( file );
    doReturn( mockRepo ).when( fileService ).getRepository();
  }

  @Test( expected = InvalidParameterException.class )
  public void testDoGetFileOrDirAsDownload_NullPathId() throws Throwable {
    fileService.doGetFileOrDirAsDownload( "userAgent", null, "true" );
  }

  @Test( expected = InvalidParameterException.class )
  public void testDoGetFileOrDirAsDownload_EmptyPathId() throws Throwable {
    fileService.doGetFileOrDirAsDownload( "userAgent", "", "false" );
  }

  @Test( expected = IllegalSelectorException.class )
  public void testDoGetFileOrDirAsDownload_InvalidPath() throws Throwable {
    String pathId = ":invalid:path";
    String path = fileService.idToPath( pathId );
    doReturn( false ).when( fileService ).isPathValid( path );

    fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "true" );
  }

  @Test( expected = FileNotFoundException.class )
  public void testDoGetFileOrDirAsDownload_FileNotFound() throws Throwable {
    String pathId = ":home:user:file.txt";
    String path = fileService.idToPath( pathId );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, null );

    fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "true" );
  }

  @Test( expected = PentahoAccessControlException.class )
  public void testDoGetFileOrDirAsDownload_NoDownloadPermission() throws Throwable {
    String pathId = ":home:user:file.txt";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );

    doThrow( new PentahoAccessControlException( "Access denied" ) )
      .when( fileService ).validateDownloadAccess( path );

    fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "true" );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_File_WithManifest() throws Throwable {
    String pathId = ":home:user:file.txt";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );
    when( mockFile.isFolder() ).thenReturn( false );
    when( mockFile.getName() ).thenReturn( "file.txt" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, true, true );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFile, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "true" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "file.txt.zip", result.getEncodedFileName() );
    verify( mockProcessor ).addExportHandler( mockHandler );
    verify( fileService ).getDownloadExportProcessor( path, true, true );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_File_WithoutManifest() throws Throwable {
    String pathId = ":home:user:document.pdf";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );
    when( mockFile.isFolder() ).thenReturn( false );
    when( mockFile.getName() ).thenReturn( "document.pdf" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, false, false );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFile, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "false" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "document.pdf", result.getEncodedFileName() );
    verify( mockProcessor ).addExportHandler( mockHandler );
    verify( fileService ).getDownloadExportProcessor( path, false, false );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_Folder_WithManifest() throws Throwable {
    String pathId = ":home:user:myFolder";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFolder = mock( RepositoryFile.class );
    when( mockFolder.isFolder() ).thenReturn( true );
    when( mockFolder.getName() ).thenReturn( "myFolder" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFolder );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, true, true );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFolder, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "true" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "myFolder.zip", result.getEncodedFileName() );
    verify( mockProcessor ).addExportHandler( mockHandler );
    verify( fileService ).getDownloadExportProcessor( path, true, true );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_Folder_WithoutManifest() throws Throwable {
    String pathId = ":public:reports";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFolder = mock( RepositoryFile.class );
    when( mockFolder.isFolder() ).thenReturn( true );
    when( mockFolder.getName() ).thenReturn( "reports" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFolder );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, true, false );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFolder, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "false" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "reports.zip", result.getEncodedFileName() );
    verify( mockProcessor ).addExportHandler( mockHandler );
    verify( fileService ).getDownloadExportProcessor( path, true, false );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_File_SpecialCharacters() throws Throwable {
    String fileName = "file name with spaces & special.txt";
    String pathId = ":home:user:" + fileName;
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );
    when( mockFile.isFolder() ).thenReturn( false );
    when( mockFile.getName() ).thenReturn( fileName );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, false, false );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFile, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "false" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );

    // Derive expected encoding using the same logic as production (URLEncoder + replace + with %20)
    String expectedEncodedFileName = URLEncoder.encode( fileName, StandardCharsets.UTF_8 )
      .replaceAll( "\\+", "%20" );
    assertEquals( expectedEncodedFileName, result.getEncodedFileName() );

    // Validate round-trip: decode and compare back to original
    String decodedFileName = URLDecoder.decode( result.getEncodedFileName(), StandardCharsets.UTF_8 );
    assertEquals( fileName, decodedFileName );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_File_NullStrWithManifest() throws Throwable {
    String pathId = ":home:user:file.txt";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );
    when( mockFile.isFolder() ).thenReturn( false );
    when( mockFile.getName() ).thenReturn( "file.txt" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, true, true );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFile, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, null );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "file.txt.zip", result.getEncodedFileName() );
    verify( fileService ).getDownloadExportProcessor( path, true, true );
  }

  @Test
  public void testDoGetFileOrDirAsDownload_File_EmptyStrWithManifest() throws Throwable {
    String pathId = ":home:user:test.xml";
    String path = fileService.idToPath( pathId );
    RepositoryFile mockFile = mock( RepositoryFile.class );
    when( mockFile.isFolder() ).thenReturn( false );
    when( mockFile.getName() ).thenReturn( "test.xml" );

    BaseExportProcessor mockProcessor = mock( BaseExportProcessor.class );
    ExportHandler mockHandler = mock( ExportHandler.class );
    StreamingOutput mockStream = mock( StreamingOutput.class );

    doReturn( true ).when( fileService ).isPathValid( path );
    setupRepositoryMock( path, mockFile );
    doNothing().when( fileService ).validateDownloadAccess( path );
    doReturn( mockProcessor ).when( fileService ).getDownloadExportProcessor( path, true, true );
    doReturn( mockHandler ).when( fileService ).getDownloadExportHandler();
    doReturn( mockStream ).when( fileService ).getDownloadStream( mockFile, mockProcessor );

    FileService.DownloadFileWrapper result = fileService.doGetFileOrDirAsDownload( "userAgent", pathId, "" );

    assertNotNull( result );
    assertEquals( mockStream, result.getOutputStream() );
    assertEquals( "test.xml.zip", result.getEncodedFileName() );
    verify( fileService ).getDownloadExportProcessor( path, true, true );
  }

  // endregion

  // region isValidFolderName

  @Test
  public void testIsValidFolderName_DecodedControl_Characters() {
    assertFalse( fileService.isValidFolderName( PATH_CONTROL_CHARACTER ) );
  }

  @Test
  public void testIsValidFolderName_EncodedControl_Characters() throws Exception {
    assertFalse( fileService.isValidFolderName( encode( PATH_CONTROL_CHARACTER ) ) );
  }

  @Test
  public void testIsValidFolderName_DecodedJapanese_Characters() {
    assertTrue( fileService.isValidFolderName( PATH_JAPANESE_CHARACTERS ) );
  }

  @Test
  public void testIsValidFolderName_EncodedJapanese_Characters() throws Exception {
    assertTrue( fileService.isValidFolderName( encode( PATH_JAPANESE_CHARACTERS ) ) );
  }

  @Test
  public void testIsValidFolderName_DecodedSpecial_Characters() {
    assertTrue( fileService.isValidFolderName( PATH_SPECIAL_CHARACTERS ) );
  }

  @Test
  public void testIsValidFolderName_EncodedSpecial_Characters() throws Exception {
    assertTrue( fileService.isValidFolderName( encode( PATH_SPECIAL_CHARACTERS ) ) );
  }

  @Test
  public void testIsValidFolderName_Decoded_InvalidCases() {
    String[] invalidNames = { ".", ".." };

    for ( String invalidName : invalidNames ) {
      assertFalse( fileService.isValidFolderName( invalidName ) );
    }
  }

  @Test
  public void testIsValidFolderName_Encoded_InvalidCases() throws Exception {
    String[] invalidNames = { ".", ".." };

    for ( String invalidName : invalidNames ) {
      assertFalse( fileService.isValidFolderName( encode( invalidName ) ) );
    }
  }

  @Test
  public void testIsValidFolderName_StrictRejectsSeparators() {
    assertFalse( fileService.isValidFolderName( "a/b", true ) );
    assertFalse( fileService.isValidFolderName( "a\\b", true ) );
    assertFalse( fileService.isValidFolderName( "a/", true ) );
  }

  @Test
  public void testIsValidFolderName_NonStrictPathDotFinalSegmentInvalid() {
    assertFalse( fileService.isValidFolderName( "a/b/.", false ) );
    assertFalse( fileService.isValidFolderName( "a/b/..", false ) );
  }

  @Test
  public void testIsValidFolderName_StrictDotsInvalid() {
    assertFalse( fileService.isValidFolderName( ".", true ) );
    assertFalse( fileService.isValidFolderName( "..", true ) );
  }

  @Test
  public void testIsValidFolderName_WhitespaceInvalid() throws Exception {
    assertFalse( fileService.isValidFolderName( " folder", true ) );
    assertFalse( fileService.isValidFolderName( "folder ", true ) );
    assertFalse( fileService.isValidFolderName( encode( " folder" ), true ) );
    assertFalse( fileService.isValidFolderName( encode( "folder " ), true ) );
  }

  @Test
  public void testIsValidFolderName_NullAndEmpty() {
    assertFalse( fileService.isValidFolderName( null, true ) );
    assertFalse( fileService.isValidFolderName( "", true ) );
  }

  @Test
  public void testIsValidFolderName_StrictReservedChar() {
    doReturn( new StringBuffer( "@$" ) ).when( fileService ).doGetReservedChars();

    assertFalse( fileService.isValidFolderName( "bad@folder", true ) );
    assertTrue( fileService.isValidFolderName( "okFolder", true ) );
  }

  @Test
  public void testIsValidFolderName_UnicodeValid() {
    assertTrue( fileService.isValidFolderName( PATH_JAPANESE_CHARACTERS, true ) );
  }

  @Test
  public void testIsValidFolderName_NonStrictChecksAllSegmentsForReserved() {
    doReturn( new StringBuffer( "!%" ) ).when( fileService ).doGetReservedChars();

    assertFalse( fileService.isValidFolderName( "a!/b/c", false ) );
    assertFalse( fileService.isValidFolderName( "a/b%/c", false ) );
    assertTrue( fileService.isValidFolderName( "a/b/c", false ) );
  }

  //endregion

  // region isValidFileName

  @Test
  public void testIsValidFileName_DecodedControl_Characters() {
    assertFalse( fileService.isValidFileName( PATH_CONTROL_CHARACTER ) );
  }

  @Test
  public void testIsValidFileName_EncodedControl_Characters() throws Exception {
    assertFalse( fileService.isValidFileName( encode( PATH_CONTROL_CHARACTER ) ) );
  }

  @Test
  public void testIsValidFileName_DecodedJapanese_Characters() {
    assertTrue( fileService.isValidFileName( PATH_JAPANESE_CHARACTERS ) );
  }

  @Test
  public void testIsValidFileName_EncodedJapanese_Characters() throws Exception {
    assertTrue( fileService.isValidFileName( encode( PATH_JAPANESE_CHARACTERS ) ) );
  }

  @Test
  public void testIsValidFileName_DecodedSpecial_Characters() {
    assertTrue( fileService.isValidFileName( PATH_SPECIAL_CHARACTERS ) );
  }

  @Test
  public void testIsValidFileName_EncodedSpecial_Characters() throws Exception {
    assertTrue( fileService.isValidFileName( encode( PATH_SPECIAL_CHARACTERS ) ) );
  }

  @Test
  public void testIsValidFileName_Decoded_InvalidCases() {
    String[] invalidNames =
      { null, "", " ", " x", "x ", " x ", "\tx", "x\t", "\tx\t", "\rx", "x\r", "\rx\r", "\nx", "x\n", "\nx\n" };

    for ( String invalidName : invalidNames ) {
      assertFalse( fileService.isValidFileName( invalidName ) );
    }
  }

  @Test
  public void testIsValidFileName_Encoded_InvalidCases() throws Exception {
    String[] invalidNames =
      { "", " x", "x ", " x ", "\tx", "x\t", "\tx\t", "\rx", "x\r", "\rx\r", "\nx", "x\n", "\nx\n" };

    for ( String invalidName : invalidNames ) {
      assertFalse(
        fileService.isValidFileName( encode( invalidName ) ) );
    }
  }

  @Test
  public void testIsValidFileName_StrictRejectsPathSeparators() {
    assertFalse( fileService.isValidFileName( "dir/sub", true ) );
    assertFalse( fileService.isValidFileName( "dir\\sub", true ) );
  }

  @Test
  public void testIsValidFileName_NonStrictAllowsPathButValidatesAllSegments() {
    doReturn( new StringBuffer( "?*" ) ).when( fileService ).doGetReservedChars();

    // '?' reserved in first segment
    assertFalse( fileService.isValidFileName( "ba?d/good/prn", false ) );
    // '*' reserved in last segment
    assertFalse( fileService.isValidFileName( "good/path/inva*lid", false ) );
    // No reserved chars
    assertTrue( fileService.isValidFileName( "good/path/valid", false ) );
  }

  @Test
  public void testIsValidFileName_StrictReservedChar() {
    doReturn( new StringBuffer( "#%" ) ).when( fileService ).doGetReservedChars();

    assertFalse( fileService.isValidFileName( "bad#name", true ) );
    assertTrue( fileService.isValidFileName( "cleanName", true ) );
  }

  @Test
  public void testIsValidFileName_StrictUnicodeValid() {
    assertTrue( fileService.isValidFileName( PATH_JAPANESE_CHARACTERS, true ) );
  }

  @Test
  public void testIsValidFileName_StrictLeadingTrailingWhitespaceInvalid() throws Exception {
    assertFalse( fileService.isValidFileName( " name", true ) );
    assertFalse( fileService.isValidFileName( "name ", true ) );
    assertFalse( fileService.isValidFileName( encode( " name" ), true ) );
    assertFalse( fileService.isValidFileName( encode( "name " ), true ) );
  }

  @Test
  public void testIsValidFileName_StrictBlankAndNull() {
    assertFalse( fileService.isValidFileName( "", true ) );
    assertFalse( fileService.isValidFileName( "   ", true ) );
    assertFalse( fileService.isValidFileName( null, true ) );
  }

  @Test
  public void testIsValidFileName_EncodedInternalWhitespaceOk() throws Exception {
    // internal spaces (not leading/trailing) allowed
    assertTrue( fileService.isValidFileName( encode( "my file name" ), true ) );
  }

  // endregion

  @Test
  public void testIsVisible() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    RepositoryFileDto visibleDto = new RepositoryFileDto();
    visibleDto.setFolder( true );
    visibleDto.setHidden( false );
    visibleDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    visibleDto.setName( "joe" );
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );

    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    doReturn( visibleDto ).when( repoWs ).getFile( "/home/joe" );

    assertEquals( "false", fileService.doGetIsVisible( ":home:suzy" ) );
    assertEquals( "true", fileService.doGetIsVisible( ":home:joe" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario_ProvidedFolderIsNull() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( null ).when( repoWs ).getFile( "/home/joe" );
    assertEquals( ClientRepositoryPaths.getRootFolderPath(), fileService.doGetDefaultLocation( ":home:joe" ) );
  }


  @Test
  public void testGetDefaultLocation_Scenario_ProvidedFolderIsVisible() {
    RepositoryFileDto visibleDto = new RepositoryFileDto();
    visibleDto.setFolder( true );
    visibleDto.setHidden( false );
    visibleDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    visibleDto.setName( "joe" );
    visibleDto.setPath( "/home/joe" );

    RepositoryFileDto homeFolderDto = new RepositoryFileDto();
    homeFolderDto.setFolder( true );
    homeFolderDto.setHidden( false );
    homeFolderDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    homeFolderDto.setName( "home" );
    homeFolderDto.setPath( "/home" );

    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( visibleDto ).when( repoWs ).getFile( "/home/joe" );
    doReturn( homeFolderDto ).when( repoWs ).getFile( "/home" );
    assertEquals( "/home/joe", fileService.doGetDefaultLocation( ":home:joe" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario__DefaultFolderIsNotProvidedPublicFolderIsVisible() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    hiddenDto.setPath( "/home/suzy" );
    RepositoryFileDto publicDto = new RepositoryFileDto();
    publicDto.setFolder( true );
    publicDto.setHidden( false );
    publicDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    publicDto.setName( "public" );
    publicDto.setPath( "/public" );
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( null );
    doReturn( publicDto ).when( repoWs ).getFile( ClientRepositoryPaths.getPublicFolderPath() );
    assertEquals( ClientRepositoryPaths.getPublicFolderPath(),  fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario_DefaultFolderIsNotProvidedPublicFolderIsHidden() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    RepositoryFileDto publicDto = new RepositoryFileDto();
    publicDto.setFolder( true );
    publicDto.setHidden( true );
    publicDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    publicDto.setName( "suzy" );
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( null );
    doReturn( publicDto ).when( repoWs ).getFile( ClientRepositoryPaths.getPublicFolderPath() );
    assertEquals( ClientRepositoryPaths.getRootFolderPath(), fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario_DefaultFolderIsVisible() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    hiddenDto.setPath( "/home/suzy" );
    RepositoryFileDto defaultDto = new RepositoryFileDto();
    defaultDto.setFolder( true );
    defaultDto.setHidden( false );
    defaultDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    defaultDto.setName( "default" );
    defaultDto.setPath( "/default-folder" );

    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( "/default-folder" );
    doReturn( defaultDto ).when( repoWs ).getFile( "/default-folder" );
    assertEquals( "/default-folder",  fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario_DefaultFolderIsHidden() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    hiddenDto.setPath( "/home/suzy" );

    RepositoryFileDto defaultDto = new RepositoryFileDto();
    defaultDto.setFolder( true );
    defaultDto.setHidden( true );
    defaultDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    defaultDto.setName( "default" );
    defaultDto.setPath( "/default-folder" );

    RepositoryFileDto publicFolderDto = new RepositoryFileDto();
    publicFolderDto.setFolder( true );
    publicFolderDto.setHidden( false );
    publicFolderDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    publicFolderDto.setName( "public" );
    publicFolderDto.setPath( "/public" );

    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( "/default-folder" );
    doReturn( defaultDto ).when( repoWs ).getFile( "/default-folder" );
    doReturn( publicFolderDto ).when( repoWs ).getFile( "/public" );
    assertEquals( ClientRepositoryPaths.getPublicFolderPath(), fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testGetDefaultLocation_Scenario_DefaultFolderIsNotAccessible() {
    RepositoryFileDto hiddenDto = new RepositoryFileDto();
    hiddenDto.setFolder( true );
    hiddenDto.setHidden( true );
    hiddenDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    hiddenDto.setName( "suzy" );
    hiddenDto.setPath( "/home/suzy" );
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    doReturn( hiddenDto ).when( repoWs ).getFile( "/home/suzy" );
    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( "/default-folder" );
    doReturn( null ).when( repoWs ).getFile( "/default-folder" );
    assertEquals( ClientRepositoryPaths.getRootFolderPath(), fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testGetDefaultLocationWhenHomeDirectoryIsHiddenAndNoDefaultFolderProvided() {
    DefaultUnifiedRepositoryWebService repository = mock( DefaultUnifiedRepositoryWebService.class );

    RepositoryFileDto userHomeFolderDto = new RepositoryFileDto();
    userHomeFolderDto.setFolder( true );
    userHomeFolderDto.setHidden( false );
    userHomeFolderDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    userHomeFolderDto.setName( "suzy" );
    userHomeFolderDto.setPath( "/home/suzy" );

    RepositoryFileDto homeFolderDto = new RepositoryFileDto();
    homeFolderDto.setFolder( true );
    homeFolderDto.setHidden( true );
    homeFolderDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    homeFolderDto.setName( "home" );
    homeFolderDto.setPath( "/home" );

    RepositoryFileDto publicFolderDto = new RepositoryFileDto();
    publicFolderDto.setFolder( true );
    publicFolderDto.setHidden( false );
    publicFolderDto.setId( RandomStringUtils.randomNumeric( 20 ) );
    publicFolderDto.setName( "public" );
    publicFolderDto.setPath( "/public" );

    when( PentahoSystem.get( ISystemConfig.class ).getProperty( eq( PentahoSystem.DEFAULT_FOLDER_WHEN_HOME_FOLDER_IS_HIDDEN_PROPERTY ) ) ).thenReturn( null );

    doReturn( repository ).when( fileService ).getRepoWs();
    doReturn( userHomeFolderDto ).when( repository ).getFile( "/home/suzy" );
    doReturn( homeFolderDto ).when( repository ).getFile( "/home" );
    doReturn( publicFolderDto ).when( repository ).getFile( "/public" );

    assertEquals( ClientRepositoryPaths.getPublicFolderPath(), fileService.doGetDefaultLocation( ":home:suzy" ) );
  }

  @Test
  public void testDoCanEdit() {
    ISystemSettings settingsService = mock( ISystemSettings.class );
    when( settingsService.getSystemSetting(  "edit-permission", "" ) ).thenReturn( RepositoryCreateAction.NAME );
    PentahoSystem.setSystemSettingsService( settingsService );

    // Test for user having a proper permission to edit
    IAuthorizationPolicy policy = mock( IAuthorizationPolicy.class );
    when( policy.isAllowed( RepositoryCreateAction.NAME ) ).thenReturn( true );
    PentahoSystem.registerObject( policy );
    assertEquals( "true", fileService.doGetCanEdit() );

    // Test for user not having a proper permission to edit
    when( settingsService.getSystemSetting(  "edit-permission", "" ) ).thenReturn( RepositoryCreateAction.NAME );
    when( policy.isAllowed( any() ) ).thenReturn( false );
    assertEquals( "false", fileService.doGetCanEdit() );

    // Test for configuration in the pentaho.xml is an empty string
    when( settingsService.getSystemSetting(  "edit-permission", "" ) ).thenReturn( "" );
    assertEquals( "true", fileService.doGetCanEdit() );

    // Test for configuration in the pentaho.xml does not exist
    when( settingsService.getSystemSetting(  "edit-permission", "" ) ).thenReturn( null );
    assertEquals( "true", fileService.doGetCanEdit() );
  }

  // region doGetCanAccess Tests

  @Test
  public void testDoGetCanAccess_RepositoryFile_UserHasReadAccess() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "0" );

    assertEquals( "true", result );
    verify( repoWs ).hasAccess( eq( "/path/to/file" ), any( List.class ) );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_UserDoesNotHaveReadAccess() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( false );

    String result = fileService.doGetCanAccess( ":path:to:file", "0" );

    assertEquals( "false", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_WritePermission() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "1" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_DeletePermission() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "2" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_AclManagementPermission() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "3" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_AllPermissions() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "4" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_MultiplePermissions() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/path/to/file" ), any( List.class ) ) ).thenReturn( true );

    String result = fileService.doGetCanAccess( ":path:to:file", "0|1|2" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_PVFS_FileExists() throws Exception {
    org.pentaho.di.core.vfs.IKettleVFS kettleVfs = mock( org.pentaho.di.core.vfs.IKettleVFS.class );
    when( kettleVfs.fileExists( eq( "pvfs://s3/mybucket/myfile.txt" ) ) ).thenReturn( true );

    doReturn( kettleVfs ).when( fileService ).getKettleVFS();

    String result = fileService.doGetCanAccess( "pvfs/s3/mybucket/myfile.txt", "0" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_PVFS_FileDoesNotExist() throws Exception {
    org.pentaho.di.core.vfs.IKettleVFS kettleVfs = mock( org.pentaho.di.core.vfs.IKettleVFS.class );
    when( kettleVfs.fileExists( eq( "pvfs://s3/mybucket/nonexistent.txt" ) ) ).thenReturn( false );

    doReturn( kettleVfs ).when( fileService ).getKettleVFS();

    String result = fileService.doGetCanAccess( "pvfs/s3/mybucket/nonexistent.txt", "0" );

    assertEquals( "false", result );
  }

  @Test
  public void testDoGetCanAccess_PVFS_ExceptionThrown() throws Exception {
    org.pentaho.di.core.vfs.IKettleVFS kettleVfs = mock( org.pentaho.di.core.vfs.IKettleVFS.class );
    when( kettleVfs.fileExists( eq( "pvfs://invalid/path" ) ) ).thenThrow( new IllegalArgumentException(
      "Invalid VFS path" ) );

    doReturn( kettleVfs ).when( fileService ).getKettleVFS();

    String result = fileService.doGetCanAccess( "pvfs/invalid/path", "0" );

    assertEquals( "false", result );
  }

  @Test
  public void testDoGetCanAccess_PVFS_MultiplePermissions() throws Exception {
    org.pentaho.di.core.vfs.IKettleVFS kettleVfs = mock( org.pentaho.di.core.vfs.IKettleVFS.class );
    when( kettleVfs.fileExists( eq( "pvfs://s3/mybucket/myfile.txt" ) ) ).thenReturn( true );

    doReturn( kettleVfs ).when( fileService ).getKettleVFS();

    String result = fileService.doGetCanAccess( "pvfs/s3/mybucket/myfile.txt", "0|1|2|3" );

    assertEquals( "true", result );
  }

  @Test
  public void testDoGetCanAccess_RepositoryFile_NoAccess_MultiplePermissions() {
    DefaultUnifiedRepositoryWebService repoWs = mock( DefaultUnifiedRepositoryWebService.class );
    doReturn( repoWs ).when( fileService ).getRepoWs();
    when( repoWs.hasAccess( eq( "/restricted/file" ), any( List.class ) ) ).thenReturn( false );

    String result = fileService.doGetCanAccess( ":restricted:file", "0|1|2" );

    assertEquals( "false", result );
  }

  // endregion

  @Test
  public void testSystemRestoreClearsBowlCacheBeforeImport() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    try {
      fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", "/tmp/test-restore.log", "DEBUG", "backup.zip" );
    } catch ( Exception ignored ) {
      // other dependencies not fully set up; clearBowlCache() is called before any other dependencies
    }

    verify( fileService ).clearBowlCache();
  }

  // region BISERVER-15532 - session expiration during restore

  private static final String EXPECTED_SESSION_EXPIRED_MESSAGE =
    Messages.getInstance().getString( "FileService.ERROR_RESTORE_SESSION_EXPIRED" );
  private static final String EXPECTED_UNABLE_TO_RECORD_EXPIRATION_MESSAGE =
    Messages.getInstance().getString( "FileService.ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION" );
  private static final String EXPECTED_UNABLE_TO_PROBE_MESSAGE =
    Messages.getInstance().getString( "FileService.ERROR_UNABLE_TO_PROBE_SESSION" );
  private static final String EXPECTED_UNABLE_TO_COMPLETE_LOG_MESSAGE =
    Messages.getInstance().getString( "FileService.ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG" );

  /**
   * Captures what {@code FileService} writes to the server log, so the messages reporting a failure of the restore-log
   * machinery itself can be asserted. Those messages never reach the restore log or the client — the server log is the
   * only place they appear — so this is the only way to pin them.
   */
  private StringWriter serverLog;
  private Appender serverLogAppender;

  /**
   * Fails when a key does not resolve. {@code MessageUtil} degrades a missing key to {@code !key!} rather than
   * throwing, and the assertions below compare the server log against the same resolved constant, so without this the
   * message could silently become the raw key and every other assertion in the test would still pass.
   */
  private void assertLocalized( String key, String resolvedMessage ) {
    assertNotEquals( "The message must resolve from the bundle", "!" + key + "!", resolvedMessage );
  }

  private void captureServerLog() {
    serverLog = new StringWriter();
    serverLogAppender = LogUtil.makeAppender( "biserver15532-appender", serverLog, "%m%n" );
    LogUtil.addAppender( serverLogAppender, LogManager.getLogger( FileService.class ), Level.ERROR );
  }

  @After
  public void releaseServerLog() {
    if ( serverLogAppender != null ) {
      LogUtil.removeAppender( serverLogAppender, LogManager.getLogger( FileService.class ) );
      serverLogAppender = null;
    }
  }

  @Test
  public void shouldReportUnableToRecordSessionExpirationWhenTheRestoreLogRejectsTheMessage() throws Exception {
    assertLocalized( "FileService.ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION",
      EXPECTED_UNABLE_TO_RECORD_EXPIRATION_MESSAGE );
    captureServerLog();
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    PlatformImportException importException = new PlatformImportException( "session gone" );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );
    doThrow( new RuntimeException( "restore log is unwritable" ) ).when( importLogger ).error( anyString() );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isCausedBySessionExpiration( importException ) ).thenReturn( true );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "The session expiration failure was expected" );
      } catch ( PlatformImportException expected ) {
        assertEquals( EXPECTED_SESSION_EXPIRED_MESSAGE, expected.getLocalizedMessage() );
      }
    }

    assertTrue( "The server log should explain that the expiration could not be recorded",
      serverLog.toString().contains( EXPECTED_UNABLE_TO_RECORD_EXPIRATION_MESSAGE ) );
  }

  @Test
  public void shouldReportUnableToProbeSessionWhenTheProbeFails() throws Exception {
    assertLocalized( "FileService.ERROR_UNABLE_TO_PROBE_SESSION", EXPECTED_UNABLE_TO_PROBE_MESSAGE );
    captureServerLog();
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    doThrow( new RuntimeException( "session probe blew up" ) ).when( detector ).isSessionExpired( any() );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      // The import itself succeeds; only the probe fails, and that must not fail the restore.
      fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
        "DEBUG", "backup.zip" );
    }

    assertTrue( "The server log should explain that the session could not be probed",
      serverLog.toString().contains( EXPECTED_UNABLE_TO_PROBE_MESSAGE ) );
    // An inconclusive probe must not be reported to the user as an expiry.
    verify( importLogger, never() ).error( EXPECTED_SESSION_EXPIRED_MESSAGE );
  }

  @Test
  public void shouldReportUnableToCompleteRestoreLogWhenEndJobFails() throws Exception {
    assertLocalized( "FileService.ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG", EXPECTED_UNABLE_TO_COMPLETE_LOG_MESSAGE );
    captureServerLog();
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( new IllegalStateException( "no job started for current thread" ) ).when( importLogger ).endJob();

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isSessionExpired( any() ) ).thenReturn( false );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      // A restore log that cannot be completed must not fail an otherwise successful restore.
      fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
        "DEBUG", "backup.zip" );
    }

    assertTrue( "The server log should explain that the restore log could not be completed",
      serverLog.toString().contains( EXPECTED_UNABLE_TO_COMPLETE_LOG_MESSAGE ) );
  }

  @Test
  public void shouldReportSessionExpirationWhenHttpSessionExpiresDuringRestore() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isSessionExpired( any() ) ).thenReturn( true );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "A PlatformImportException was expected because the session expired" );
      } catch ( PlatformImportException expected ) {
        assertEquals( EXPECTED_SESSION_EXPIRED_MESSAGE, expected.getMessage() );
        assertEquals( PlatformImportException.SESSION_EXPIRED, expected.getErrorStatus() );
        // Detected by the final session probe rather than by a failure, so there is no cause to chain.
        assertNull( expected.getCause() );
      }
    }

    verify( importLogger ).error( EXPECTED_SESSION_EXPIRED_MESSAGE );
  }

  @Test
  public void shouldNotReportSessionExpirationWhenSessionRemainsValid() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isSessionExpired( any() ) ).thenReturn( false );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
        "DEBUG", "backup.zip" );
    }

    verify( importLogger, never() ).error( anyString() );
    verify( importLogger ).endJob();
  }

  @Test
  public void shouldPropagateUnrelatedImportException() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    PlatformImportException importException = new PlatformImportException( "unrelated import failure" );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isCausedBySessionExpiration( importException ) ).thenReturn( false );
    when( detector.isSessionExpired( any() ) ).thenReturn( false );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "The original PlatformImportException was expected" );
      } catch ( PlatformImportException expected ) {
        assertSame( importException, expected );
      }
    }

    verify( importLogger, never() ).error( anyString() );
    verify( importLogger ).endJob();
  }

  @Test
  public void shouldNotLetTheRestoreLogFailureMaskTheSessionExpiration() throws Exception {
    // The restore log is written from a finally block. A failure to record the expiration there must not skip
    // endJob()/the log flush, nor replace the curated SESSION_EXPIRED failure with the logging failure.
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    PlatformImportException importException = new PlatformImportException( "session gone" );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );
    doThrow( new RuntimeException( "restore log is unwritable" ) ).when( importLogger ).error( anyString() );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isCausedBySessionExpiration( importException ) ).thenReturn( true );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "The session expiration failure was expected" );
      } catch ( PlatformImportException expected ) {
        assertEquals( EXPECTED_SESSION_EXPIRED_MESSAGE, expected.getLocalizedMessage() );
        assertEquals( PlatformImportException.SESSION_EXPIRED, expected.getErrorStatus() );
        assertSame( importException, expected.getCause() );
      }
    }

    // The log was still finalised and flushed despite the logger failing.
    verify( importLogger ).error( anyString() );
    verify( importLogger ).endJob();
  }

  @Test
  public void shouldFlushTheRestoreLogWhenEndJobAndTheSessionProbeBothFail() throws Exception {
    // The whole finally block must be total: neither the session probe nor endJob() may skip the flush to disk or
    // replace the failure the caller is waiting for.
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    PlatformImportException importException = new PlatformImportException( "unrelated import failure" );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );
    doThrow( new IllegalStateException( "no job started for current thread" ) ).when( importLogger ).endJob();

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isCausedBySessionExpiration( importException ) ).thenReturn( false );
    doThrow( new RuntimeException( "session probe blew up" ) ).when( detector ).isSessionExpired( any() );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "The original import failure was expected" );
      } catch ( PlatformImportException expected ) {
        // The caller still learns what actually went wrong, not that the logger or the probe failed.
        assertSame( importException, expected );
      }
    }

    // An inconclusive probe must not put a session-expiry explanation into the log.
    verify( importLogger, never() ).error( anyString() );
    verify( importLogger ).endJob();
    // The flush still ran: the descriptor was closed rather than leaked.
    assertTrue( logFile.exists() );
  }

  @Test
  public void shouldPropagateUnrelatedRuntimeException() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    RuntimeException importException = new RuntimeException( "unrelated runtime failure" );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );

    SessionExpirationDetector detector = mock( SessionExpirationDetector.class );
    when( detector.isCausedBySessionExpiration( importException ) ).thenReturn( false );
    when( detector.isSessionExpired( any() ) ).thenReturn( false );
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "The original RuntimeException was expected" );
      } catch ( RuntimeException expected ) {
        assertSame( importException, expected );
      }
    }

    verify( importLogger, never() ).error( anyString() );
    verify( importLogger ).endJob();
  }

  @Test
  public void shouldReportSessionExpirationFromImportExceptionCause() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    IPlatformImporter importer = mock( IPlatformImporter.class );
    IRepositoryImportLogger importLogger = mock( IRepositoryImportLogger.class );
    PlatformImportException importException = new PlatformImportException( "General Error",
      new IllegalStateException( "getAttribute: Session already invalidated" ) );
    when( importer.getRepositoryImportLogger() ).thenReturn( importLogger );
    doThrow( importException ).when( importer ).importFile( any() );

    SessionExpirationDetector detector = new SessionExpirationDetector();
    fileService.setSessionExpirationDetector( detector );

    File logFile = File.createTempFile( "biserver15532", ".log" );
    logFile.deleteOnExit();

    try ( MockedStatic<PentahoSystem> pentahoSystem = mockStatic( PentahoSystem.class );
          MockedStatic<PentahoSessionHolder> sessionHolder = mockStatic( PentahoSessionHolder.class ) ) {
      pentahoSystem.when( () -> PentahoSystem.get( IPlatformImporter.class ) ).thenReturn( importer );
      sessionHolder.when( PentahoSessionHolder::getSession ).thenReturn( mock( IPentahoSession.class ) );

      try {
        fileService.systemRestore( mock( InputStream.class ), "true", "true", "true", logFile.getAbsolutePath(),
          "DEBUG", "backup.zip" );
        fail( "A PlatformImportException was expected because the session expired" );
      } catch ( PlatformImportException expected ) {
        assertEquals( EXPECTED_SESSION_EXPIRED_MESSAGE, expected.getMessage() );
        assertEquals( PlatformImportException.SESSION_EXPIRED, expected.getErrorStatus() );
        // The failure that revealed the expiration is chained so the stack identifying the layer that rejected the
        // invalidated session is not lost.
        assertSame( importException, expected.getCause() );
      }
    }

    verify( importLogger ).error( EXPECTED_SESSION_EXPIRED_MESSAGE );
    verify( importLogger ).endJob();
  }

  // endregion

  @Test
  public void testSystemBackupClearsBowlCacheBeforeExport() throws Exception {
    doReturn( true ).when( fileService ).doCanAdminister();
    doNothing().when( fileService ).clearBowlCache();

    try {
      fileService.systemBackup( "/tmp/test-backup.log", "DEBUG", "backup.zip" );
    } catch ( Exception ignored ) {
      // other dependencies not fully set up; clearBowlCache() is called before any other dependencies
    }

    verify( fileService ).clearBowlCache();
  }

  @Test
  public void testCreateFileClearsBowlCacheBeforeWrite() throws Exception {
    doNothing().when( fileService ).clearBowlCache();
    InputStream inputStream = mock( InputStream.class );
    RepositoryFileOutputStream repositoryFileOutputStream = mock( RepositoryFileOutputStream.class );
    doReturn( repositoryFileOutputStream ).when( fileService ).getRepositoryFileOutputStream( nullable( String.class ) );
    doReturn( 1 ).when( fileService ).copy( inputStream, repositoryFileOutputStream );

    fileService.createFile( UTF_8, PATH_SPECIAL_CHARACTERS, inputStream );

    verify( fileService ).clearBowlCache();
  }

  @Test
  public void testDoGetFileOrDirAsDownloadClearsBowlCacheBeforeRead() throws Throwable {
    doNothing().when( fileService ).clearBowlCache();

    try {
      fileService.doGetFileOrDirAsDownload( "userAgent", null, "true" );
    } catch ( Throwable ignored ) {
      // clearBowlCache() is called before the pathId null check
    }

    verify( fileService ).clearBowlCache();
  }

  @Test
  public void testDoGetFileAsInlineClearsBowlCacheBeforeRead() throws Exception {
    doNothing().when( fileService ).clearBowlCache();

    try {
      fileService.doGetFileAsInline( ":home:user:file.txt" );
    } catch ( Exception ignored ) {
      // other dependencies not fully set up; clearBowlCache() is called before any other dependencies
    }

    verify( fileService ).clearBowlCache();
  }

  @Test
  public void testDoGetFileOrDirClearsBowlCacheBeforeRead() throws Exception {
    doNothing().when( fileService ).clearBowlCache();

    try {
      fileService.doGetFileOrDir( ":home:user:file.txt" );
    } catch ( Exception ignored ) {
      // other dependencies not fully set up; clearBowlCache() is called before any other dependencies
    }

    verify( fileService ).clearBowlCache();
  }

  private static String encode( String pathControlCharacter ) throws UnsupportedEncodingException {
    return URLEncoder.encode( pathControlCharacter, UTF_8 );
  }
}

package org.codehaus.mojo.templating;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.CRC32;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.codehaus.plexus.util.FileUtils;

public abstract class AbstractFilterSourcesMojo
    extends AbstractMojo
{
    private int CHECKSUM_BUFFER = 4096;
    private int copied;
    protected abstract File getSourceDirectory();
    protected abstract File getOutputDirectory();

    /**
     * The character encoding scheme to be applied when filtering resources.
     */
    @Parameter( defaultValue = "${project.build.sourceEncoding}" )
    private String encoding;

    /**
     * Expression preceded with the String won't be interpolated \${foo} will be replaced with ${foo}
     */
    @Parameter( property = "maven.resources.escapeString" )
    protected String escapeString;

    /**
     * Set of delimiters for expressions to filter within the resources. These delimiters are specified in the form
     * 'beginToken*endToken'. If no '*' is given, the delimiter is assumed to be the same for start and end. So, the
     * default filtering delimiters might be specified as:
     * 
     * <pre>
     * &lt;delimiters&gt;
     *   &lt;delimiter&gt;${*}&lt;/delimiter&gt;
     *   &lt;delimiter&gt;@&lt;/delimiter&gt;
     * &lt;/delimiters&gt;
     * </pre>
     * 
     * Since the '@' delimiter is the same on both ends, we don't need to specify '@*@' (though we can).
     */
    @Parameter
    protected List<String> delimiters;

    /**
     * Controls whether the default delimiters are included in addition to those configured {@link #delimiters}. Does
     * not have any effect if {@link #delimiters} is empty when the defaults will be included anyway.
     */
    @Parameter( defaultValue = "true" )
    protected boolean useDefaultDelimiters;

    @Parameter( defaultValue = "${session}", required = true, readonly = true )
    private MavenSession session;

    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    protected MavenProject project;

    @Parameter( defaultValue = "false" )
    protected boolean overwrite;

    @Parameter( defaultValue = "true" )
    protected boolean skipPoms;

    @Component( hint = "default" )
    protected MavenResourcesFiltering mavenResourcesFiltering;

	public void execute() throws MojoExecutionException
    {
        if ( skipPoms && "pom".equals( project.getPackaging() ) )
        {
            getLog().debug(
                    "Skipping a POM project type. Change a `skipPoms` to false to run anyway." );
            return;
        }
        try
        {
            copied = 0;
            File sourceDirectory = getSourceDirectory();
            getLog().debug( "source=" + sourceDirectory + " target=" + getOutputDirectory() );

            if ( !(sourceDirectory != null && sourceDirectory.exists()) )
            {
                getLog().info(
                        "Request to add '" + sourceDirectory + "' folder. Not added since it does not exist." );
                return;
            }

            // 1 Copy with filtering the given source to target dir
            List<Resource> resources = new ArrayList<Resource>();
            Resource resource = new Resource();
            resource.setFiltering( true );
            getLog().debug( sourceDirectory.getAbsolutePath() );
            resource.setDirectory( sourceDirectory.getAbsolutePath() );
            resources.add( resource );

            Path basedir = project.getBasedir().getCanonicalFile().toPath();
            String target = project.getBuild().getDirectory();
            File tmp = basedir.resolve( target ).resolve( "templates-tmp" ).toFile();
            MavenResourcesExecution mavenResourcesExecution
                    = new MavenResourcesExecution( resources, tmp, project, encoding, Collections.emptyList(),
                            Collections.<String>emptyList(), session );
            mavenResourcesExecution.setInjectProjectBuildFilters( false );
            mavenResourcesExecution.setEscapeString( escapeString );
            mavenResourcesExecution.setOverwrite( overwrite );
            setDelimiters( mavenResourcesExecution );

            try
            {
                mavenResourcesFiltering.filterResources( mavenResourcesExecution );
            }
            catch ( MavenFilteringException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
            // 2 Copy if needed
            copyDirectoryStructure( tmp, getOutputDirectory(), getOutputDirectory() );
            FileUtils.forceDelete( tmp );
            if ( getLog().isInfoEnabled() )
            {
                if ( copied > 0 )
                {
                    getLog().info( "Copied `" + copied + "` to output directory: " + getOutputDirectory() );
                }
                else
                {
                    getLog().info( "No files have bean copied. Up to date." );
                }
            }

            // 3 Add that dir to sources
            addSourceFolderToProject( this.project );

            if ( getLog().isInfoEnabled() )
            {
                getLog().info( "Source directory: " + getOutputDirectory() + " added." );
            }
        }
        catch ( IOException ex )
        {
            throw new MojoExecutionException( ex.getMessage(), ex );
        }
    }
    protected abstract void addSourceFolderToProject( MavenProject mavenProject );

    private void setDelimiters( MavenResourcesExecution mavenResourcesExecution )
    {
        // if these are NOT set, just use the defaults, which are '${*}' and '@'.
        if ( delimiters != null && !delimiters.isEmpty() )
        {
            LinkedHashSet<String> delims = new LinkedHashSet<String>();
            if ( useDefaultDelimiters )
            {
                delims.addAll( mavenResourcesExecution.getDelimiters() );
            }

            for ( String delim : delimiters )
            {
                if ( delim == null )
                {
                    // FIXME: ${filter:*} could also trigger this condition. Need a better long-term solution.
                    delims.add( "${*}" );
                }
                else
                {
                    delims.add( delim );
                }
            }

            mavenResourcesExecution.setDelimiters( delims );
        }
    }

    private void copyDirectoryStructure( final File sourceDirectory, final File destinationDirectory,
            final File rootDestinationDirectory ) throws IOException
    {
        if ( sourceDirectory == null )
        {
            throw new IOException( "source directory can't be null." );
        }

        if ( destinationDirectory == null )
        {
            throw new IOException( "destination directory can't be null." );
        }

        if ( sourceDirectory.equals( destinationDirectory ) )
        {
            throw new IOException( "source and destination are the same directory." );
        }

        if ( !sourceDirectory.exists() )
        {
            throw new IOException( "Source directory doesn't exists (" + sourceDirectory.getAbsolutePath() + ")." );
        }

        File[] files = sourceDirectory.listFiles();

        String sourcePath = sourceDirectory.getAbsolutePath();

        for ( File file : files )
        {
            if ( file.equals( rootDestinationDirectory ) )
            {
                // We don't copy the destination directory in itself
                continue;
            }

            String dest = file.getAbsolutePath();

            dest = dest.substring( sourcePath.length() + 1 );

            File destination = new File( destinationDirectory, dest );

            if ( file.isFile() )
            {
                destination = destination.getParentFile();

                if ( isDiffrent( file, destination ) )
                {
                    copied++;
                    FileUtils.copyFileToDirectory( file, destination );
                }
            }
            else if ( file.isDirectory() )
            {
                if ( !destination.exists() && !destination.mkdirs() )
                {
                    throw new IOException(
                            "Could not create destination directory '" + destination.getAbsolutePath() + "'." );
                }

                copyDirectoryStructure( file, destination, rootDestinationDirectory );
            }
            else
            {
                throw new IOException( "Unknown file type: " + file.getAbsolutePath() );
            }
        }
    }

    private boolean isDiffrent( final File file, final File directory ) throws IOException
    {
        File targetFile = directory.toPath().resolve( file.getName() ).toFile().getAbsoluteFile();
        return !targetFile.canRead() || getCrc32OfFile( file ) != getCrc32OfFile( targetFile );
    }

    private long getCrc32OfFile( final File target ) throws IOException
    {
        FileInputStream fis = null;
        try
        {
            fis = new FileInputStream( target );
            CRC32 crcMaker = new CRC32();
            byte[] buffer = new byte[CHECKSUM_BUFFER];
            int bytesRead;
            while ( (bytesRead = fis.read( buffer )) != -1 )
            {
                crcMaker.update( buffer, 0, bytesRead );
            }
            return crcMaker.getValue();
        }
        catch ( FileNotFoundException ex )
        {
            close( fis );
            throw new IOException( ex.getLocalizedMessage(), ex );
        }
        finally
        {
            close( fis );
        }
    }

    private void close( Closeable is ) throws IOException
    {
        if ( is != null )
        {
            is.close();
        }
    }
}

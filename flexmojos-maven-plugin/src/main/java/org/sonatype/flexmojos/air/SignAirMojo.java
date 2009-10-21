package org.sonatype.flexmojos.air;

import static org.sonatype.flexmojos.common.FlexExtension.SWC;
import static org.sonatype.flexmojos.common.FlexExtension.SWF;
import static org.sonatype.flexmojos.common.FlexExtension.AIR;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;
import org.sonatype.flexmojos.utilities.FileInterpolationUtil;

import com.adobe.air.AIRPackager;
import com.adobe.air.Listener;
import com.adobe.air.Message;

/**
 * @goal sign-air
 * @requiresDependencyResolution compile
 * @author Marvin Froeder
 */
public class SignAirMojo
    extends AbstractMojo
{

    /**
     * The type of keystore, determined by the keystore implementation.
     *
     * @parameter default-value="pkcs12"
     */
    private String storetype;

    /**
     * @parameter default-value="${basedir}/src/main/resources/sign.p12"
     */
    private File keystore;

    /**
     * @parameter expression="${project}"
     */
    private MavenProject project;

    /**
     * @parameter default-value="${project.build.finalName}.air"
     */
    private String outputName;

    /**
     * @parameter default-value="${basedir}/src/main/resources/descriptor.xml"
     */
    private File descriptorTemplate;

    /**
     * @parameter
     * @required
     */
    private String storepass;

    /**
     * @parameter default-value="${project.build.directory}/air"
     */
    private File airOutput;

    /**
     * Plugin classpath.
     *
     * @parameter expression="${plugin.artifacts}"
     * @required
     * @readonly
     */
    protected List<Artifact> pluginClasspath;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        AIRPackager airPackager = new AIRPackager();
        try
        {
            File output = new File( project.getBuild().getDirectory(), outputName );
            airPackager.setOutput(output);
            airPackager.setDescriptor(getAirDescriptor());

            KeyStore keyStore = KeyStore.getInstance(storetype);
            keyStore.load(new FileInputStream(keystore.getAbsolutePath()), storepass.toCharArray());
            String alias = keyStore.aliases().nextElement();
            airPackager.setPrivateKey((PrivateKey) keyStore.getKey(alias, storepass.toCharArray()));
            airPackager.setSignerCertificate(keyStore.getCertificate(alias));
            airPackager.setCertificateChain(keyStore.getCertificateChain(alias));

            if ( project.getPackaging().equals( AIR ) )
            {
                Set<Artifact> deps = project.getDependencyArtifacts();
                for ( Artifact artifact : deps )
                {
                    if ( SWF.equals( artifact.getType() ) || SWC.equals( artifact.getType() ) )
                    {
                        airPackager.addSourceWithPath(artifact.getFile(), artifact.getFile().getName());
                    }
                }
            }
            else
            {
                airPackager.addSourceWithPath(project.getArtifact().getFile(), project.getArtifact().getFile().getName());
            }

            project.getArtifact().setFile( output );

            airPackager.setListener(new Listener()
                {
                    public void message(Message message)
                    {
                        getLog().info( "  " + message );
                    }

                    public void progress(int soFar, int total)
                    {
                        getLog().info( "  completed " + soFar + " of " + total);
                    }
                });
            airPackager.createAIR();
        }
        catch ( MojoExecutionException e )
        {
            // do not handle
            throw e;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Error invoking AIR api", e );
        }
        finally
        {
            airPackager.close();
        }
    }


    @SuppressWarnings( "unchecked" )
    private String[] getArgs()
        throws MojoExecutionException
    {
        List<String> args = new ArrayList<String>();
        args.add( "-package" );
        args.add( "-storetype" );
        args.add( storetype );
        args.add( "-keystore" );
        args.add( keystore.getAbsolutePath() );
        args.add( "-storepass" );
        args.add( storepass );
        File output = new File( project.getBuild().getDirectory(), outputName );
        args.add( output.getAbsolutePath() );
        File xml = getAirDescriptor();
        args.add( xml.getAbsolutePath() );
        if ( project.getPackaging().equals( AIR ) )
        {
            Set<Artifact> deps = project.getDependencyArtifacts();
            for ( Artifact artifact : deps )
            {
                if ( SWF.equals( artifact.getType() ) || SWC.equals( artifact.getType() ) )
                {
                    try
                    {
                        FileUtils.copyFileToDirectory( artifact.getFile(), airOutput );
                    }
                    catch ( IOException e )
                    {
                        throw new MojoExecutionException( "Failed to copy " + artifact, e );
                    }
                    args.add( new File( airOutput, artifact.getFile().getName() ).getAbsolutePath() );
                }
            }
        }
        else
        {
            args.add( project.getArtifact().getFile().getAbsolutePath() );
        }

        project.getArtifact().setFile( output );

        return args.toArray( new String[args.size()] );
    }

    @SuppressWarnings( "unchecked" )
    private File getAirDescriptor()
        throws MojoExecutionException
    {
        File output = null;
        if ( project.getPackaging().equals( AIR ) )
        {
            Set<Artifact> deps = project.getDependencyArtifacts();
            for ( Artifact artifact : deps )
            {
                if ( SWF.equals( artifact.getType() ) || SWC.equals( artifact.getType() ) )
                {
                    output = artifact.getFile();
                    break;
                }
            }
        }
        else
        {
            output = project.getArtifact().getFile();
        }

        File dest = new File( airOutput, project.getBuild().getFinalName() + "-descriptor.xml" );
        try
        {
            FileInterpolationUtil.copyFile( descriptorTemplate, dest, Collections.singletonMap( "output",
                                                                                                output.getName() ) );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to copy air template", e );
        }
        return dest;
    }
}
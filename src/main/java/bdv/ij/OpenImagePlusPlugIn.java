package bdv.ij;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import net.imglib2.FinalDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import bdv.BigDataViewer;
import bdv.ViewerImgLoader;
import bdv.cache.CacheControl;
import bdv.ij.util.ProgressWriterIJ;
import bdv.img.imagestack.ImageStackImageLoader;
import bdv.img.virtualstack.VirtualStackImageLoader;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.viewer.DisplayMode;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerOptions;
import bdv.viewer.VisibilityAndGrouping;
import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.process.LUT;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;

/**
 * ImageJ plugin to show the current image in BigDataViewer.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
@Plugin(type = Command.class,
	menuPath = "Plugins>BigDataViewer>Open Current Image")
public class OpenImagePlusPlugIn implements Command
{
	public static void main( final String[] args )
	{
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );
		new ImageJ();


//		IJ.run("Confocal Series (2.2MB)");
//		IJ.run("Confocal Series (2.2MB)");
//		IJ.run("Fly Brain (1MB)");
		
//		ImagePlus ip1 = IJ.openImage("/groups/saalfeld/home/bogovicj/tmp/mri-stack.tif");
//		ImagePlus ip2 = IJ.openImage("/groups/saalfeld/home/bogovicj/tmp/flybrain.tif");
		
		ImagePlus ip1 = IJ.openImage("/groups/saalfeld/home/bogovicj/tmp/confocal-series.tif");
		ImagePlus ip2 = IJ.openImage("/groups/saalfeld/home/bogovicj/tmp/confocal_grad.tif");

		ip1.show();
		ip2.show();

		new OpenImagePlusPlugIn().run();
	}

	@Override
	public void run()
	{
		if ( ij.Prefs.setIJMenuBar )
			System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		int nImages = WindowManager.getImageCount();
		// get the current image
		final ImagePlus curr = WindowManager.getCurrentImage();
		// make sure there is one
		if ( curr == null )
		{
			IJ.showMessage( "Please open an image first." );
			return;
		}

		final int[] idList = WindowManager.getIDList();
		final String[] nameList = new String[ nImages ];
		GenericDialog gd = new GenericDialog("Images to open");
		for( int i = 0; i < nImages; i++ )
		{
		    ImagePlus imp = WindowManager.getImage( idList[ i ]);
		    nameList[ i ] = imp.getTitle();
		    gd.addCheckbox( nameList[i], imp == curr );
		}

		gd.showDialog();
		if (gd.wasCanceled()) return;

		final ArrayList< ConverterSetup > converterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList<>();

		AbstractSpimData< ? > spimData;
		CacheControl cache = null;
		int setup_id_offset = 0;
		for( int i = 0; i < nImages; i++ )
		{
			if( !gd.getNextBoolean() )
				continue;

			ImagePlus imp = WindowManager.getImage( idList[ i ]);
			spimData = load( imp, converterSetups, sources, setup_id_offset );
			if( spimData != null )
				cache = ( ( ViewerImgLoader ) spimData.getSequenceDescription().getImgLoader() ).getCacheControl();
			
			setup_id_offset += imp.getChannel();
		}

		int nTimepoints = 1;
		final BigDataViewer bdv = BigDataViewer.open( converterSetups, sources,
				nTimepoints, cache,
				"BigDataViewer", new ProgressWriterIJ(), ViewerOptions.options() );

		final SetupAssignments sa = bdv.getSetupAssignments();
		final VisibilityAndGrouping vg = bdv.getViewer().getVisibilityAndGrouping();
		vg.setFusedEnabled( true );

		int channel_offset = 0;
		for( int i = 0; i < nImages; i++ )
		{
			ImagePlus imp = WindowManager.getImage( idList[ i ]);
			if ( imp.isComposite() )
			{
				transferChannelSettings( channel_offset, ( CompositeImage ) imp, sa, vg );
				channel_offset += imp.getNChannels();
			}
			else
				transferImpSettings( imp, sa );
		}
	}

	protected AbstractSpimData< ? > load( ImagePlus imp, ArrayList< ConverterSetup > converterSetups, ArrayList< SourceAndConverter< ? >> sources, 
			int setup_id_offset )
	{
		// check the image type
		switch ( imp.getType() )
		{
		case ImagePlus.GRAY8:
		case ImagePlus.GRAY16:
		case ImagePlus.GRAY32:
		case ImagePlus.COLOR_RGB:
			break;
		default:
			IJ.showMessage( "Only 8, 16, 32-bit images and RGB images are supported currently!" );
			return null;
		}

		// check the image dimensionality
		if ( imp.getNDimensions() < 3 )
		{
			IJ.showMessage( "Image must be at least 3-dimensional!" );
			return null;
		}

		// get calibration and image size
		final double pw = imp.getCalibration().pixelWidth;
		final double ph = imp.getCalibration().pixelHeight;
		final double pd = imp.getCalibration().pixelDepth;
		String punit = imp.getCalibration().getUnit();
		if ( punit == null || punit.isEmpty() )
			punit = "px";
		final FinalVoxelDimensions voxelSize = new FinalVoxelDimensions( punit, pw, ph, pd );
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getNSlices();
		final FinalDimensions size = new FinalDimensions( new int[] { w, h, d } );

		// propose reasonable mipmap settings
//		final ExportMipmapInfo autoMipmapSettings = ProposeMipmaps.proposeMipmaps( new BasicViewSetup( 0, "", size, voxelSize ) );

//		imp.getDisplayRangeMin();
//		imp.getDisplayRangeMax();

		// create ImgLoader wrapping the image
		final BasicImgLoader imgLoader;
		if ( imp.getStack().isVirtual() )
		{
			switch ( imp.getType() )
			{
			case ImagePlus.GRAY8:
				imgLoader = VirtualStackImageLoader.createUnsignedByteInstance( imp );
				break;
			case ImagePlus.GRAY16:
				imgLoader = VirtualStackImageLoader.createUnsignedShortInstance( imp );
				break;
			case ImagePlus.GRAY32:
				imgLoader = VirtualStackImageLoader.createFloatInstance( imp );
				break;
			case ImagePlus.COLOR_RGB:
			default:
				imgLoader = VirtualStackImageLoader.createARGBInstance( imp );
				break;
			}
		}
		else
		{
			switch ( imp.getType() )
			{
			case ImagePlus.GRAY8:
				imgLoader = ImageStackImageLoader.createUnsignedByteInstance( imp );
				break;
			case ImagePlus.GRAY16:
				imgLoader = ImageStackImageLoader.createUnsignedShortInstance( imp );
				break;
			case ImagePlus.GRAY32:
				imgLoader = ImageStackImageLoader.createFloatInstance( imp );
				break;
			case ImagePlus.COLOR_RGB:
			default:
				imgLoader = ImageStackImageLoader.createARGBInstance( imp );
				break;
			}
		}

		final int numTimepoints = imp.getNFrames();
		final int numSetups = imp.getNChannels();

		// create setups from channels
		final HashMap< Integer, BasicViewSetup > setups = new HashMap<>( numSetups );
		for ( int s = 0; s < numSetups; ++s )
		{
			final BasicViewSetup setup = new BasicViewSetup( s, String.format( imp.getTitle() + " channel %d", s + 1 ), size, voxelSize );
			setup.setAttribute( new Channel( setup_id_offset + s + 1 ) );
			setups.put( s, setup );
		}

		// create timepoints
		final ArrayList< TimePoint > timepoints = new ArrayList<>( numTimepoints );
		for ( int t = 0; t < numTimepoints; ++t )
			timepoints.add( new TimePoint( t ) );
		final SequenceDescriptionMinimal seq = new SequenceDescriptionMinimal( new TimePoints( timepoints ), setups, imgLoader, null );

		// create ViewRegistrations from the images calibration
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		sourceTransform.set( pw, 0, 0, 0, 0, ph, 0, 0, 0, 0, pd, 0 );
		final ArrayList< ViewRegistration > registrations = new ArrayList<>();
		for ( int t = 0; t < numTimepoints; ++t )
			for ( int s = 0; s < numSetups; ++s )
				registrations.add( new ViewRegistration( t, s, sourceTransform ) );

		final File basePath = new File(".");
		final AbstractSpimData< ? > spimData = new SpimDataMinimal( basePath, seq, new ViewRegistrations( registrations ) );
		WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData );
		BigDataViewer.initSetups( spimData, converterSetups, sources );

		return spimData;
	}

	protected void transferChannelSettings( int channel_offset, final CompositeImage ci, final SetupAssignments setupAssignments, final VisibilityAndGrouping visibility )
	{
		final int nChannels = ci.getNChannels();
		final int mode = ci.getCompositeMode();
		final boolean transferColor = mode == IJ.COMPOSITE || mode == IJ.COLOR;
		for ( int c = 0; c < nChannels; ++c )
		{
			final LUT lut = ci.getChannelLut( c + 1 );
			final ConverterSetup setup = setupAssignments.getConverterSetups().get( channel_offset + c );
			if ( transferColor )
				setup.setColor( new ARGBType( lut.getRGB( 255 ) ) );
			setup.setDisplayRange( lut.min, lut.max );
		}
		if ( mode == IJ.COMPOSITE )
		{
			final boolean[] activeChannels = ci.getActiveChannels();
			for ( int i = 0; i < activeChannels.length; ++i )
				visibility.setSourceActive( i, activeChannels[ i ] );
		}
		else
			visibility.setDisplayMode( DisplayMode.SINGLE );
		visibility.setCurrentSource( ci.getChannel() - 1 );
	}

	protected void transferImpSettings( final ImagePlus imp, final SetupAssignments setupAssignments )
	{
		final ConverterSetup setup = setupAssignments.getConverterSetups().get( 0 );
		setup.setDisplayRange( imp.getDisplayRangeMin(), imp.getDisplayRangeMax() );
	}
}

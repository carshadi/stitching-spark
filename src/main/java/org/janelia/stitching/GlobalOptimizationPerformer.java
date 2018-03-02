package org.janelia.stitching;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.stitching.ImagePlusTimePoint;

// based on the GlobalOptimization class from the original Fiji's Stitching plugin repository
public class GlobalOptimizationPerformer
{
	private static final double DAMPNESS_FACTOR = 0.9;

	public Map< Integer, Tile< ? > > lostTiles = null;

	public boolean translationOnlyStitching = false;

	public int replacedTilesTranslation = 0;

	static private final java.io.PrintStream originalOut, suppressedOut;
	static
	{
		originalOut = System.out;
		suppressedOut = new java.io.PrintStream(new java.io.OutputStream() {
			@Override public void write(final int b) {}
		}) {
			@Override public void flush() {}
			@Override public void close() {}
			@Override public void write(final int b) {}
			@Override public void write(final byte[] b) {}
			@Override public void write(final byte[] buf, final int off, final int len) {}
			@Override public void print(final boolean b) {}
			@Override public void print(final char c) {}
			@Override public void print(final int i) {}
			@Override public void print(final long l) {}
			@Override public void print(final float f) {}
			@Override public void print(final double d) {}
			@Override public void print(final char[] s) {}
			@Override public void print(final String s) {}
			@Override public void print(final Object obj) {}
			@Override public void println() {}
			@Override public void println(final boolean x) {}
			@Override public void println(final char x) {}
			@Override public void println(final int x) {}
			@Override public void println(final long x) {}
			@Override public void println(final float x) {}
			@Override public void println(final double x) {}
			@Override public void println(final char[] x) {}
			@Override public void println(final String x) {}
			@Override public void println(final Object x) {}
			@Override public java.io.PrintStream printf(final String format, final Object... args) { return this; }
			@Override public java.io.PrintStream printf(final java.util.Locale l, final String format, final Object... args) { return this; }
			@Override public java.io.PrintStream format(final String format, final Object... args) { return this; }
			@Override public java.io.PrintStream format(final java.util.Locale l, final String format, final Object... args) { return this; }
			@Override public java.io.PrintStream append(final CharSequence csq) { return this; }
			@Override public java.io.PrintStream append(final CharSequence csq, final int start, final int end) { return this; }
			@Override public java.io.PrintStream append(final char c) { return this; }
		};
	}

	public int remainingGraphSize, remainingPairs;
	public double avgDisplacement, maxDisplacement;

	public synchronized static void suppressOutput()
	{
		System.setOut( suppressedOut );
	}
	public synchronized static void restoreOutput()
	{
		System.setOut( originalOut );
	}

	public ArrayList< ImagePlusTimePoint > optimize(
			final Vector< ComparePointPair > comparePointPairs,
			final SerializableStitchingParameters params ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		return optimize( comparePointPairs, params, null );
	}

	public ArrayList< ImagePlusTimePoint > optimize(
			final Vector< ComparePointPair > comparePointPairs,
			final SerializableStitchingParameters params,
			final PrintWriter logWriter ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		// create a set of tiles
		final Set< Tile< ? > > tilesSet = new HashSet<>();
		int pairsAdded = 0;
		for ( final ComparePointPair comparePointPair : comparePointPairs )
		{
			if ( comparePointPair.getIsValidOverlap() )
			{
				++pairsAdded;

				final Tile< ? > t1 = comparePointPair.getTile1();
				final Tile< ? > t2 = comparePointPair.getTile2();

				final float weight = comparePointPair.getCrossCorrelation();

				t1.addMatch( new PointMatch( comparePointPair.getPointPair().getA(), comparePointPair.getPointPair().getB(), weight ) );
				t2.addMatch( new PointMatch( comparePointPair.getPointPair().getB(), comparePointPair.getPointPair().getA(), weight ) );

				t1.addConnectedTile( t2 );
				t2.addConnectedTile( t1 );

				tilesSet.add( t1 );
				tilesSet.add( t2 );
			}
		}

		writeLog( logWriter, "Pairs above the threshold: " + pairsAdded + ", pairs total = " + comparePointPairs.size() );

		if ( tilesSet.isEmpty() )
			return null;

		// print graph sizes
		final TreeMap< Integer, Integer > graphSizeToCount = getGraphsSize( tilesSet );
		writeLog( logWriter, "Number of tile graphs = " + graphSizeToCount.values().stream().mapToInt( Number::intValue ).sum() );
		for ( final Entry< Integer, Integer > entry : graphSizeToCount.descendingMap().entrySet() )
			writeLog( logWriter, "   " + entry.getKey() + " tiles: " + entry.getValue() + " graphs" );

		// trash everything but the largest graph
		final int numTilesBeforeRetainingLargestGraph = tilesSet.size();
		preserveOnlyLargestGraph( tilesSet );
		final int numTilesAfterRetainingLargestGraph = tilesSet.size();

		writeLog( logWriter, "Using the largest graph of size " + numTilesAfterRetainingLargestGraph + " (throwing away " + ( numTilesBeforeRetainingLargestGraph - numTilesAfterRetainingLargestGraph ) + " tiles from smaller graphs)" );
		remainingGraphSize = numTilesAfterRetainingLargestGraph;

		remainingPairs = countRemainingPairs( tilesSet, comparePointPairs );

		// if all tiles have underlying translation models, consider this stitching configuration to be translation-only
		translationOnlyStitching = true;
		for ( final Tile< ? > tile : tilesSet )
		{
			if ( !( tile.getModel() instanceof TranslationModel2D || tile.getModel() instanceof TranslationModel3D ) )
			{
				translationOnlyStitching = false;
				break;
			}
		}

		// when stitching using higher-order models, avoid degenerate models by shifting point matches for each tile by [-1.0, 1.0] px so that they are not on the same plane
		if ( !translationOnlyStitching )
			shiftPointMatches( comparePointPairs );

		// if some of the tiles do not have enough point matches for a high-order model, fall back to simpler model
		replacedTilesTranslation = ensureEnoughPointMatches( tilesSet );

		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles( tilesSet );

		// find a useful fixed tile
		for ( final Tile< ? > tile : tilesSet )
		{
			if ( tile.getConnectedTiles().size() > 0 )
			{
				tc.fixTile( tile );
				break;
			}
		}

		tc.preAlign();

		final int iterations = 5000;

		long elapsed = System.nanoTime();

		tc.optimizeSilently(
				new ErrorStatistic( iterations + 1 ),
				0, // max allowed error -- does not matter as maxPlateauWidth=maxIterations
				iterations,
				iterations,
				translationOnlyStitching ? 1 : DAMPNESS_FACTOR
			);

		elapsed = System.nanoTime() - elapsed;

		writeLog( logWriter, "Optimization round took " + elapsed/1e9 + "s" );

		final double avgError = tc.getError();
		final double maxError = tc.getMaxError();

		// new way of finding biggest error to look for the largest displacement
		double longestDisplacement = 0;
		for ( final Tile< ? > t : tc.getTiles() )
			for ( final PointMatch p :  t.getMatches() )
				longestDisplacement = Math.max( p.getDistance(), longestDisplacement );

		writeLog( logWriter, "" );
		writeLog( logWriter, "Max pairwise match displacement: " + longestDisplacement );
		writeLog( logWriter, String.format( "avg error: %.2fpx", avgError ) );
		writeLog( logWriter, String.format( "max error: %.2fpx", maxError ) );

		avgDisplacement = avgError;
		maxDisplacement = maxError;

		lostTiles = new TreeMap<>();
		for ( final ComparePointPair comparePointPair : comparePointPairs )
			for ( final ImagePlusTimePoint t : new ImagePlusTimePoint[] { comparePointPair.getTile1(), comparePointPair.getTile2() } )
				lostTiles.put( t.getImpId(), t );
		for ( final Tile< ? > t : tc.getTiles() )
			lostTiles.remove( ( ( ImagePlusTimePoint ) t ).getImpId() );
		System.out.println( "Tiles lost: " + lostTiles.size() );


		// create a list of image informations containing their positions
		final ArrayList< ImagePlusTimePoint > imageInformationList = new ArrayList< >();
		for ( final Tile< ? > t : tc.getTiles() )
			imageInformationList.add( (ImagePlusTimePoint)t );

		Collections.sort( imageInformationList );

		return imageInformationList;
	}

	private static void shiftPointMatches( final Vector< ComparePointPair > comparePointPairs )
	{
		final Random rnd = new Random( 69997 ); // repeatable results
		for ( final ComparePointPair comparePointPair : comparePointPairs )
		{
			final int dim = comparePointPair.getPointPair().getA().getL().length;
			final double[] shift = new double[ dim ];
			for ( int d = 0; d < dim; ++d )
				shift[ d ] = rnd.nextDouble() * 2 - 1; // random shift in the range of [-1.0, 1.0] px

			for ( final Point point : comparePointPair.getPointPair().toArray() )
				for ( int d = 0; d < dim; ++d )
					point.getL()[ d ] += shift[ d ];
		}
	}

	private static int ensureEnoughPointMatches( final Set< Tile< ? > > tilesSet )
	{
		int numTilesReplacedWithTranslation = 0;

		final Set< Tile< ? > > newTilesSet = new HashSet<>();
		for ( final Tile< ? > tile : tilesSet )
		{
			if ( tile.getMatches().size() < tile.getModel().getMinNumMatches() )
			{
				final int dim = tile.getMatches().iterator().next().getP1().getL().length;
				final Model< ? > replacementModel = dim == 2 ? new TranslationModel2D() : new TranslationModel3D();

				final Tile< ? > replacementTile = new ImagePlusTimePoint(
						( ( ImagePlusTimePoint ) tile ).getImagePlus(),
						( ( ImagePlusTimePoint ) tile ).getImpId(),
						( ( ImagePlusTimePoint ) tile ).getTimePoint(),
						replacementModel,
						( ( ImagePlusTimePoint ) tile ).getElement()
					);

				replacementTile.addMatches( tile.getMatches() );
				for ( final Tile< ? > connectedTile : tile.getConnectedTiles() )
				{
					// do not use removeConnectedTile() because it tries too hard and removes point matches from both sides which we would like to preserve
					connectedTile.getConnectedTiles().remove( tile );
					connectedTile.addConnectedTile( replacementTile );
					replacementTile.addConnectedTile( connectedTile );
				}

				newTilesSet.add( replacementTile );
				++numTilesReplacedWithTranslation;
			}
			else
			{
				newTilesSet.add( tile );
			}
		}

		tilesSet.clear();
		tilesSet.addAll( newTilesSet );

		return numTilesReplacedWithTranslation;
	}

	private static TreeMap< Integer, Integer > getGraphsSize( final Set< Tile< ? > > tilesSet )
	{
		final TreeMap< Integer, Integer > graphSizeToCount = new TreeMap<>();

		final ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tilesSet );
		for ( final Set< Tile< ? > > graph : graphs )
		{
			final int graphSize = graph.size();
			graphSizeToCount.put( graphSize, graphSizeToCount.getOrDefault( graphSize, 0 ) + 1 );
		}

		return graphSizeToCount;
	}

	private static void preserveOnlyLargestGraph( final Set< Tile< ? > > tilesSet )
	{
		// get components
		final ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tilesSet );
		int largestGraphSize = 0, largestGraphId = -1;
		for ( int i = 0; i < graphs.size(); ++i )
		{
			final int graphSize = graphs.get( i ).size();
			if ( graphSize > largestGraphSize )
			{
				largestGraphSize = graphSize;
				largestGraphId = i;
			}
		}

		// retain the largest component
		final ArrayList< Tile< ? > > largestGraph = new ArrayList<>();
		largestGraph.addAll( graphs.get( largestGraphId ) );
		tilesSet.clear();
		tilesSet.addAll( largestGraph );
	}

	private static int countRemainingPairs( final Set< Tile< ? > > remainingTilesSet, final Vector< ComparePointPair > comparePointPairs )
	{
		int remainingPairs = 0;
		for ( final ComparePointPair comparePointPair : comparePointPairs )
			if ( comparePointPair.getIsValidOverlap() && remainingTilesSet.contains( comparePointPair.getTile1() ) && remainingTilesSet.contains( comparePointPair.getTile2() ) )
				++remainingPairs;
		return remainingPairs;
	}

	private static void writeLog( final PrintWriter logWriter, final String log )
	{
		if ( logWriter != null )
			logWriter.println( log );
		System.out.println( log );
	}
}

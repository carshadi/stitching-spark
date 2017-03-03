package org.janelia.stitching.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.stitching.SerializablePairWiseStitchingResult;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.Utils;

public class GroupTilesByTimestamp
{
	public static void main( final String[] args ) throws Exception
	{
		final TileInfo[] tiles = TileInfoJSONProvider.loadTilesConfiguration( args[ 0 ] );
		for ( int i = 1; i < tiles.length; i++ )
			if ( tiles[ i - 1].getIndex().intValue() + 1 != tiles[ i ].getIndex().intValue() )
				throw new Exception( "Tiles are not sorted by index" );

		final Map< Integer, Long > timestamps = Utils.getTilesTimestampsMap( tiles );
		final List< List< TileInfo > > tileGroups = new ArrayList<>();
		for ( int i = 0; i < tiles.length; i++ )
		{
			if ( i == 0 || timestamps.get( tiles[ i - 1 ].getIndex() ).longValue() > timestamps.get( tiles[ i ].getIndex() ).longValue() )
				tileGroups.add( new ArrayList<>() );
			tileGroups.get( tileGroups.size() - 1 ).add( tiles[ i ] );
		}

		for ( int i = 0; i < tileGroups.size(); i++ )
		{
			final List< Integer > tileGroupIndexes = new ArrayList<>();
			for ( final TileInfo tileInGroup : tileGroups.get( i ) )
				tileGroupIndexes.add( tileInGroup.getIndex() );
			System.out.println( "Group " + i + ": " + tileGroupIndexes.size() + " tiles" );
		}

		final String pairwiseSuffix = "_pairwise";
		final List< SerializablePairWiseStitchingResult > shifts = TileInfoJSONProvider.loadPairwiseShifts( Utils.addFilenameSuffix( args[ 0 ], pairwiseSuffix ) );
		for ( int i = 0; i < tileGroups.size(); i++ )
		{
			final Set< Integer > groupTileIndexes = new HashSet<>();
			for ( final TileInfo tile : tileGroups.get( i ) )
				groupTileIndexes.add( tile.getIndex() );
			final List< SerializablePairWiseStitchingResult > groupShifts = new ArrayList<>();
			for ( final SerializablePairWiseStitchingResult shift : shifts )
				if ( groupTileIndexes.contains( shift.getTilePair().getA().getIndex() ) && groupTileIndexes.contains( shift.getTilePair().getB().getIndex() ) )
					groupShifts.add( shift );

			final String groupSuffix = "_group" + i;
			TileInfoJSONProvider.saveTilesConfiguration( tileGroups.get( i ).toArray( new TileInfo[ 0 ] ), Utils.addFilenameSuffix( args[ 0 ], groupSuffix ) );
			TileInfoJSONProvider.savePairwiseShifts( groupShifts, Utils.addFilenameSuffix( Utils.addFilenameSuffix( args[ 0 ], groupSuffix ), pairwiseSuffix ) );
		}
	}
}

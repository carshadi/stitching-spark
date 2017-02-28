package net.imglib2.realtransform;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import bigwarp.landmarks.LandmarkTableModel;
import jitk.spline.ThinPlateR2LogRSplineKernelTransform;

public class PropagateLandmarkChanges
{

	private int maxIters = 5000;
	private double tolerance = 1e-4;

	/**
	 * Propagates changes to a reference transformation to a new transformation.
	 * 
	 * Three spaces: canonical "C", intermediate: "I", and source "S"
	 * IntermXfm transforms I to C.
	 * ChangeXfm maps S to C, but was obtained by working on a transformed version of I.
	 * if IntermXfm changes, ChangeXfm will have to change to still be consistent with I,
	 * this method updates ChangeXfm appropriately.
	 * 
	 * @param oldIntermXfmF the old mapping from I to C
	 * @param newIntermXfmF the new mapping from I to C
	 * @param changeXfmF the old mapping from S to C (through old I)
	 * @param outXfmF the new mapping from S to C (through new I)
	 * @throws IOException if anything failed
	 */
	public void propagateLabels( 
			String oldIntermXfmF,
			String newIntermXfmF,
			String changeXfmF,
			String outXfmF ) throws IOException
	{
		LandmarkTableModel oldRefLtm = loadLtm( new File( oldIntermXfmF ));
		LandmarkTableModel newRefLtm = loadLtm( new File( newIntermXfmF ));
		LandmarkTableModel changeLtm = loadLtm( new File( changeXfmF ));
		LandmarkTableModel outLtm = new LandmarkTableModel( 3 );

		ThinPlateR2LogRSplineKernelTransform oldRefXfm = oldRefLtm.getTransform();
		ThinPlateR2LogRSplineKernelTransform newRefXfm = newRefLtm.getTransform();

		ArrayList<Double[]> fixedPointsToChange = changeLtm.getPoints( false ); // get fixed points to change
		int i = 0;
		for( Double[] pt : fixedPointsToChange )
		{
			double[] res = new double[ 3 ];
			double[] newFixed = new double[ 3 ];

			oldRefXfm.apply( toPrimitive( pt ), res );
			newRefXfm.inverse( res, newFixed, tolerance, maxIters );

			outLtm.add( toPrimitive( changeLtm.getPoint( true, i )), true );
			outLtm.setPoint(i, false, newFixed);

			i++;
		}

		outLtm.save( new File( outXfmF ));
	}

	public static double[] toPrimitive( Double[] pt )
	{
		double[] out = new double[ pt.length ];
		for( int i = 0; i < pt.length; i++ )
			out[ i ] = pt[ i ].doubleValue();

		return out;
	}

	public static LandmarkTableModel loadLtm( File f ) throws IOException
	{
		LandmarkTableModel ltm = new LandmarkTableModel( 3 );
		ltm.load( f );
		return ltm;
	}

	public int getMaxIters() {
		return maxIters;
	}

	public void setMaxIters(int maxIters) {
		this.maxIters = maxIters;
	}

	public double getTolerance() {
		return tolerance;
	}

	public void setTolerance(double tolerance) {
		this.tolerance = tolerance;
	}

	public static void main(String[] args) throws IOException
	{

		String oldRefXfmF = args[ 0 ];
		String newRefXfmF = args[ 1 ];
		String changeXfmF = args[ 2 ];
		String outXfmF = args[ 3 ];

//		String oldRefXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/landmarks_testProp/ch0_14-13.csv";
//		String newRefXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/landmarks_testProp/ch0_14-13_mod.csv";
//		String changeXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/landmarks_testProp/ch0_15-14.csv";
//		String outXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/landmarks_testProp/ch0_15-14_prop.csv";


//		String oldRefXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/test_propagation/ref_orig.csv";
//		String newRefXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/test_propagation/ref_new.csv";
//		String changeXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/test_propagation/changeUs.csv";
//		String outXfmF = "/groups/saalfeld/home/bogovicj/projects/igor_illumiation-correction/test_propagation/result.csv";

		PropagateLandmarkChanges propagator = new PropagateLandmarkChanges();
		propagator.propagateLabels(oldRefXfmF, newRefXfmF, changeXfmF, outXfmF);

	}
}
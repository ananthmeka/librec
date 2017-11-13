package librec.ranking;

import librec.data.*;
import librec.intf.IterativeRecommender;
import librec.metric.ITimeMetric;
import librec.util.Logs;
import librec.util.Strings;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <h3>WRMF: Weighted Regularized Matrix Factorization.</h3>
 * <p>
 * This implementation refers to the method proposed by Hu et al. at ICDM 2008.
 * <p>
 * <ul>
 * <li><strong>Binary ratings:</strong> Pan et al., One-class Collaborative
 * Filtering, ICDM 2008.</li>
 * <li><strong>Real ratings:</strong> Hu et al., Collaborative filtering for
 * implicit feedback datasets, ICDM 2008.</li>
 * </ul>
 *
 * @author wkq
 */
@Configuration("binThold, alpha, factors, regU, regI, numIters")
public class WRMF extends IterativeRecommender {
	private float alpha;
	private SparseMatrix CuiI;// C_{ui} = alpha * r_{ui} C_{ui}-1
	private SparseMatrix Pui;// P_{ui} = 1 if r_{ui}>0 or P_{ui} = 0
	private List<List<Integer>> userItemList, itemUserList;

	public WRMF(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {
		super(trainMatrix, testMatrix, fold);

		isRankingPred = true; // item recommendation
		
		// no need to update learning rate
		lRate = -1;
		
		alpha = algoOptions.getFloat("-alpha");
		// checkBinary();
	}

	@Override
	protected void initModel() {
		P = new DenseMatrix(numUsers, numFactors);
		Q = new DenseMatrix(numItems, numFactors);
		// initialize model
		if (initByNorm) {
			System.out.println("initByNorm");
			P.init(initMean, initStd);
			Q.init(initMean, initStd);
		} else {
			P.init(); // P.init(smallValue);
			Q.init(); // Q.init(smallValue);
		}

		// predefined CuiI and Pui
		CuiI = new SparseMatrix(trainMatrix);
		Pui = new SparseMatrix(trainMatrix);
		for (MatrixEntry me : trainMatrix) {
			int u = me.row();
			int i = me.column();
			CuiI.set(u, i, alpha * 1);
			// CuiI.set(u, i, Math.log(1.0 + Math.pow(10, alpha) * me.get()));
			Pui.set(u, i, 1.0d);
		}

		this.userItemList = new ArrayList<>();
		this.itemUserList = new ArrayList<>();

		for (int u = 0; u < numUsers; u++) {
			userItemList.add(trainMatrix.getColumns(u));
		}
		for (int i = 0; i < numItems; i++) {
			itemUserList.add(trainMatrix.getRows(i));
		}
	}
	public void printIterationMetrics() throws Exception {
    	Logs.debug ("ANANTH DEBUG : NEW METRCIS FOR ITERATION : BEGIN  ") ;
		measures.init(this);
		// evaluation
		if (verbose)
			Logs.debug("{}{} evaluate test data ... ", algoName, foldInfo);
		// TODO: to predict ratings only, or do item recommendations only
        if (measures.hasRankingMetrics() && (measures.hasRatingMetrics())) {
        	//Logs.debug ("ANANTH DEBUG : LOOP ! ") ;
            evalRankings();
            evalRatings();
        } else if (measures.hasRatingMetrics()) {
        	//Logs.debug ("ANANTH DEBUG : LOOP 2 ") ;
            evalRatings();
        } else if (measures.hasRankingMetrics()) {
        	//Logs.debug ("ANANTH DEBUG : LOOP 3 ") ;
            evalRankings();
        } else {
        	//Logs.debug ("ANANTH DEBUG : LOOP 4 ") ;
            Logs.debug("No metrics found.");
        }
        
		String measurements = measures.getEvalResultString();

        // added metric names
        String evalInfo = "Metrics: " + measures.getMetricNamesString() + "\n";
        evalInfo += algoName + foldInfo + ": " + measurements ;

		if (!isRankingPred)
			evalInfo += "\tView: " + view;

		//if (fold > 0)
			Logs.debug(evalInfo);
	    	Logs.debug ("ANANTH DEBUG : NEW METRCIS FOR ITERATION : END  ") ;
	}
	
	@Override
	protected void buildModel() throws Exception {
		// To be consistent with the symbols in the paper
		DenseMatrix X = P, Y = Q;
		SparseMatrix IuMatrix = DiagMatrix.eye(numFactors).scale(regU);
		SparseMatrix IiMatrix = DiagMatrix.eye(numFactors).scale(regI);
		for (int iter = 1; iter <= numIters; iter++) {

			Logs.debug("ANANTH : DEBUG - ITERATION NUMBER {} ", iter ) ;
			// Step 1: update user factors;
			DenseMatrix Yt = Y.transpose();
			DenseMatrix YtY = Yt.mult(Y);
			for (int u = 0; u < numUsers; u++) {
				if (verbose && (u + 1) % numUsers == 0)
					Logs.debug("{}{} runs at iteration = {}, user = {}/{} {}", algoName, foldInfo, iter, u + 1,
							numUsers, new Date());

				DenseMatrix YtCuI = new DenseMatrix(numFactors, numItems);
				for (int i : userItemList.get(u)) {
					for (int k = 0; k < numFactors; k++) {
						YtCuI.set(k, i, Y.get(i, k) * CuiI.get(u, i));
					}
				}

				// YtY + Yt * (Cu - I) * Y
				DenseMatrix YtCuY = new DenseMatrix(numFactors, numFactors);
				for (int k = 0; k < numFactors; k++) {
					for (int f = 0; f < numFactors; f++) {
						double value = 0.0;
						for (int i : userItemList.get(u)) {
							value += YtCuI.get(k, i) * Y.get(i, f);
						}
						YtCuY.set(k, f, value);
					}
				}
				YtCuY = YtCuY.add(YtY);
				// (YtCuY + lambda * I)^-1
				// lambda * I can be pre-difined because every time is the same.
				DenseMatrix Wu = (YtCuY.add(IuMatrix)).inv();
				// Yt * (Cu - I) * Pu + Yt * Pu
				DenseVector YtCuPu = new DenseVector(numFactors);
				for (int f = 0; f < numFactors; f++) {
					for (int i : userItemList.get(u)) {
						YtCuPu.add(f, Pui.get(u, i) * (YtCuI.get(f, i) + Yt.get(f, i)));
					}
				}

				DenseVector xu = Wu.mult(YtCuPu);
				// udpate user factors
				X.setRow(u, xu);
			}

			// Step 2: update item factors;
			DenseMatrix Xt = X.transpose();
			DenseMatrix XtX = Xt.mult(X);
			for (int i = 0; i < numItems; i++) {
				if (verbose && (i + 1) % numItems == 0)
					Logs.debug("{}{} runs at iteration = {}, item = {}/{} {}", algoName, foldInfo, iter, i + 1,
							numItems, new Date());

				DenseMatrix XtCiI = new DenseMatrix(numFactors, numUsers);
				// actually XtCiI is a sparse matrix
				// Xt * (Ci-I)
				for (int u : itemUserList.get(i)) {
					for (int k = 0; k < numFactors; k++) {
						XtCiI.set(k, u, X.get(u, k) * CuiI.get(u, i));
					}
				}
				// XtX + Xt * (Ci - I) * X
				DenseMatrix XtCiX = new DenseMatrix(numFactors, numFactors);
				for (int k = 0; k < numFactors; k++) {
					for (int f = 0; f < numFactors; f++) {
						double value = 0.0;
						for (int u : itemUserList.get(i)) {
							value += XtCiI.get(k, u) * X.get(u, f);
						}
						XtCiX.set(k, f, value);
					}
				}
				XtCiX = XtCiX.add(XtX);

				// (XtCuX + lambda * I)^-1
				// lambda * I can be pre-difined because every time is the same.
				DenseMatrix Wi = (XtCiX.add(IiMatrix)).inv();
				// Xt * (Ci - I) * Pu + Xt * Pu
				DenseVector XtCiPu = new DenseVector(numFactors);
				for (int f = 0; f < numFactors; f++) {
					for (int u : itemUserList.get(i)) {
						XtCiPu.add(f, Pui.get(u, i) * (XtCiI.get(f, u) + Xt.get(f, u)));
					}
				}

				DenseVector yi = Wi.mult(XtCiPu);
				// udpate item factors
				Y.setRow(i, yi);
			}
			// ANANTH - CUSTOM CHANGE - BEGIN 
			Logs.debug("ANANTH DEBUG : iteration = {}, iterForMetrics = {}", iter, numItersPerMetrics);
			if ( iter % numItersPerMetrics == 0 )  
				printIterationMetrics() ;
			// ANANTH - CUSTOM CHANGE - END 
		}
	}

	@Override
	public String toString() {
		return Strings.toString(new Object[] { binThold, alpha, numFactors, regU, regI, numIters }, ",");
	}

}

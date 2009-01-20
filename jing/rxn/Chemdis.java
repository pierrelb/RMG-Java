//!********************************************************************************
//!
//!    RMG: Reaction Mechanism Generator                                            
//!
//!    Copyright: Jing Song, MIT, 2002, all rights reserved
//!     
//!    Author's Contact: jingsong@mit.edu
//!
//!    Restrictions:
//!    (1) RMG is only for non-commercial distribution; commercial usage
//!        must require other written permission.
//!    (2) Redistributions of RMG must retain the above copyright
//!        notice, this list of conditions and the following disclaimer.
//!    (3) The end-user documentation included with the redistribution,
//!        if any, must include the following acknowledgment:
//!        "This product includes software RMG developed by Jing Song, MIT."
//!        Alternately, this acknowledgment may appear in the software itself,
//!        if and wherever such third-party acknowledgments normally appear.
//!  
//!    RMG IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED 
//!    WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES 
//!    OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
//!    DISCLAIMED.  IN NO EVENT SHALL JING SONG BE LIABLE FOR  
//!    ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
//!    CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT 
//!    OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;  
//!    OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF  
//!    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT  
//!    (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF 
//!    THE USE OF RMG, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//! 
//!******************************************************************************

package jing.rxn;

import java.io.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.StringTokenizer;
import jing.chem.LennardJones;
import jing.chem.Species;
import jing.chem.SpeciesDictionary;
import jing.chem.ThreeFrequencyModel;
import jing.chemParser.ChemParser;
import jing.mathTool.MathTool;
import jing.param.Pressure;
import jing.param.Temperature;
import jing.rxnSys.CoreEdgeReactionModel;
import jing.rxnSys.ReactionSystem;

/**
 * Contains data members and methods for interacting with Chemdis, a pressure-
 * dependent rate coefficient estimator developed by Chang, Bozzelli, and Dean.
 * This was the original pressure-dependent module in RMG, but has since been
 * replaced with the fast master equation (FAME) algorithm due to a licensing
 * change with Chemdis.
 * <p>
 * For more information on Chemdis, see the following reference:
 * <p>
 * A. Y. Chang, J. W. Bozzelli, and A. M. Dean. "Kinetic Analysis of Complex
 * Chemical Activation and Unimolecular Dissociation Reactions using QRRK
 * Theory and the Modified Strong Collision Approximation." Z. Phys. Chem
 * 214 (11), p. 1533-1568 (2000).
 * 
 * @author jwallen
 */
public class Chemdis implements PDepKineticsEstimator {

	/**
	 * The number of times the FAME module has been called for any network
	 * since the inception of this RMG execution.
	 */
	private static int runCount = 0;
	
	/**
	 * Runs a pressure-dependent calculation by preparing the input file,
	 * calling the Chemdis executable, parsing the output file, and updating
	 * the network/system accordingly.
	 * @param pdn The pressure-dependent reaction network of interest
	 * @param rxnSystem The reaction system of interest
	 */
	public void runPDepCalculation(PDepNetwork pdn, ReactionSystem rxnSystem,
			CoreEdgeReactionModel cerm) {
        
		// No update needed if network is not altered
		if (pdn.getAltered() == false)
			return;
		
		// Determine wells and reactions; skip if no reactions in network
		LinkedList<PDepIsomer> uniIsomers = pdn.getUniIsomers();
		LinkedList<PDepIsomer> multiIsomers = pdn.getMultiIsomers();
		LinkedList<PDepReaction> pathReactions = pdn.getPathReactions();
		if (pathReactions.size() == 0) {
			System.out.println("Warning: Empty pressure-dependent network detected. Skipping.");
			return;
		}
		
		// A Chemdis call must be made for each isomer in the network, as the
		// result of the Chemdis call is rate coefficients for the reaction of
		// that isomer to all other isomers
		LinkedList<PDepReaction> netReactionList = pdn.getNetReactions();
		netReactionList.clear();
		try {
		
			for (int i = 0; i < pdn.getUniIsomers().size(); i++) {
				PDepIsomer entryIsomer = pdn.getUniIsomers().get(i);
				PDepReaction entryReaction = getEntryReaction(pdn, entryIsomer);
				if (entryReaction != null) {
					writeInputFile(pdn, rxnSystem, entryIsomer, entryReaction);
					executeChemdis();
					netReactionList.addAll(readOutputFile(pdn, rxnSystem, entryIsomer));
				}
			}
			for (int n = 0; n < pdn.getMultiIsomers().size(); n++) {
				PDepIsomer entryIsomer = pdn.getMultiIsomers().get(n);
				PDepReaction entryReaction = getEntryReaction(pdn, entryIsomer);
				if (entryReaction != null) {
					writeInputFile(pdn, rxnSystem, entryIsomer, entryReaction);
					executeChemdis();
					netReactionList.addAll(readOutputFile(pdn, rxnSystem, entryIsomer));
				}
			}
			
		}
		catch (Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();
			System.exit(0);
		}
		
		// Set reverse reactions
		for (int i = 0; i < netReactionList.size(); i++) {
			PDepReaction rxn1 = netReactionList.get(i);
			for (int j = 0; j < netReactionList.size(); j++) {
				PDepReaction rxn2 = netReactionList.get(j);
				if (rxn1.getReactant().equals(rxn2.getProduct()) &&
					rxn2.getReactant().equals(rxn1.getProduct())) {
					rxn1.setReverseReaction(rxn2);
					rxn2.setReverseReaction(rxn1);
					netReactionList.remove(rxn2);
				}
			}
		}

		// Update reaction lists (sort into included and nonincluded)
		pdn.updateReactionLists(cerm);
		
        // Reset altered flag
        pdn.setAltered(false);
		
    }
	
	public PDepReaction getEntryReaction(PDepNetwork pdn, PDepIsomer isomer) {
		for (ListIterator<PDepReaction> iter = pdn.getPathReactions().listIterator(); iter.hasNext(); ) {
			PDepReaction rxn = iter.next();
			if (rxn.getReactant().equals(isomer) || rxn.getProduct().equals(isomer))
				return rxn;
		}
		return null;
	}
	
	public void executeChemdis() {
		
		String dir = System.getProperty("RMG.workingDirectory");
		
		// Chemdis system call
		try {
			String[] command = {dir + "/software/chemdis/chemdis.exe"};
			File runningDir = new File("chemdis");
			Process chemdis = Runtime.getRuntime().exec(command, null, runningDir);                     
			InputStream ips = chemdis.getInputStream();
			InputStreamReader is = new InputStreamReader(ips);
			BufferedReader br = new BufferedReader(is);
			String line=null;
			while ( (line = br.readLine()) != null) {
				//System.out.println(line);
			}
			int exitValue = chemdis.waitFor();
		}
		catch (Exception e) {
			System.out.println("Error in run chemdis!");
			System.exit(0);
		}
	}
	
	/**
	 * Creates the input file needed by Chemdis that represents a pressure-
	 * dependent reaction network.
	 * @param pdn The reaction network of interest
	 * @param rxnSystem The reaction system of interest
	 */
	public void writeInputFile(PDepNetwork pdn, ReactionSystem rxnSystem, 
			PDepIsomer entryIsomer, PDepReaction entryReaction) throws Exception {
		
		try {
        	// "fort.10" is the name of the Chemdis import file
        	File chemdis_input = new File("chemdis/fort.10");
        	FileWriter fw = new FileWriter(chemdis_input);
        	
			// Create header to write to file
			String str = writeNetworkHeader(pdn, rxnSystem, entryIsomer, entryReaction);
			
			// Add wells to file string
			for (int i = 0; i < pdn.getUniIsomers().size(); i++) {
				PDepIsomer isom = pdn.getUniIsomers().get(i);
				str += "Well " + String.valueOf(i) + '\n';
				str += writeWellInput(pdn, isom, isom.equals(entryIsomer));
			}
			
			// Write string to file
			fw.write(str);
        	fw.close();
        }
        catch (IOException e) {
        	System.out.println("Error writing input file for Chemdis");
        	System.exit(0);
        }

	}
	
	/**
	 * Creates a string representing the information about a species as needed
	 * for Chemdis input.
	 * @param species The species of interest
	 * @return A string containing the input file information about the species
	 */
	public String writeSpeciesInput(Species species) {
		return "SPC" + String.valueOf(species.getID());
	}
	
	/**
	 * Creates a string representing the information about a reaction as needed
	 * for Chemdis input.
	 * @param rxn The reaction of interest
	 * @return A string containing the input file information about the reaction
	 */
	public String writeReactionInput(PDepReaction rxn) {

		// Not really sure that this is how the reaction type ought to be set
		String type = "";
		if (rxn.getType() == PDepReaction.Type.ISOMERIZATION)
			type = "ISOMER";
		else if (rxn.getType() == PDepReaction.Type.ASSOCIATION)
			type = "REACTANT";
		else if (rxn.getType() == PDepReaction.Type.DISSOCIATION)
			type = "PRODUCT";
		else
			return "";
		
		String str = type + "\n";
        for (Iterator iter = rxn.getProductList().iterator(); iter.hasNext(); ) {
        	
        	Species spe = (Species) iter.next();
			str += writeSpeciesInput(spe) + " + ";
        }
        str = str.substring(0, str.length()-3) + '\n'; 
        
        Kinetics k = rxn.getHighPKinetics(); 
        if (k == null) 
			throw new NullPointerException();
        
        str += Double.toString(k.getAValue()) + '\t';	
        str += Double.toString(k.getNValue()) + '\t';	
        str += "0.0\t";	
        if (k.getEValue()<0) {
            System.out.println("Warning! Changed E from "+Double.toString(k.getEValue())+" to 0 for Chemdis calculation.");
            str += "0.0\n";
        } else {
            str += Double.toString(k.getEValue()) + '\n';
        }
        
        return str;	
	}
	
	/**
	 * Creates a string representing the information about a well as needed
	 * for Chemdis input.
	 * @param isomer The unimolecular isomer of interest
	 * @return A string containing the input file information about the well
	 */
	public String writeWellInput(PDepNetwork pdn, PDepIsomer isomer,
			boolean isEntryIsomer) {
        
		// A "well" refers specifically to a unimolecular isomer
		if (!isomer.isUnimolecular())
			return "";
		
		String str = "";
		
		// Get the species represented by the well and write its input
		Species spe = isomer.getSpecies(0);
        str += writeSpeciesInput(spe) + '\n';
		
		// Write the three-frequency model for the species
        str += "FREQ\n"; 
        ThreeFrequencyModel tfm = spe.getThreeFrequencyModel();
        int fnumber = tfm.getFrequencyNumber();
        str += " " + String.valueOf(fnumber) + " ";
        double [] freq = tfm.getFrequency();
        double [] deg = tfm.getDegeneracy();
        for (int i=0; i<fnumber; i++) {
        	double f = freq[i];
        	double d = deg[i];
        	str += MathTool.formatDouble(f, 7, 1) + " ";
        	str += MathTool.formatDouble(d, 7, 1) + " ";
        }
        str += '\n';
        
		// Write the reactions connected to the well
		for (ListIterator<PDepReaction> iter = pdn.getPathReactions().listIterator(); iter.hasNext(); ) {
			PDepReaction rxn = iter.next();
			if (isEntryIsomer) {
				if (rxn.getReactant().equals(isomer)) {
					if (!rxn.hasReverseReaction())
						rxn.generateReverseReaction();
					str += writeReactionInput((PDepReaction) rxn.getReverseReaction());
				}
				else if (rxn.getProduct().equals(isomer))
					str += writeReactionInput(rxn);
			}
			else {
				if (rxn.getReactant().equals(isomer))
					str += writeReactionInput(rxn);
				else if (rxn.getProduct().equals(isomer)) {
					if (!rxn.hasReverseReaction())
						rxn.generateReverseReaction();
					str += writeReactionInput((PDepReaction) rxn.getReverseReaction());
				}
			}
		}
		
		return str;  
    }
	
	/**
	 * Creates the header to the input file needed by Chemdis that represents 
	 * a pressure-dependent reaction network.
	 * @param pdn The reaction network of interest
	 * @param rxnSystem The reaction system of interest
	 * @param entryIsomer The "entrance" isomer
	 * @param entryReaction The "entrance" reaction
	 * @return The header to the Chemdis input file
	 */
	private String writeNetworkHeader(PDepNetwork pdn, ReactionSystem rxnSystem,
			PDepIsomer entryIsomer, PDepReaction entryReaction) throws Exception {
        
		// Check that inputs are non-null
		if (pdn == null || rxnSystem == null)
			throw new NullPointerException();
		
		// Write network identifier
		String str = "RMG-Generated Partial Network " + Integer.toString(++runCount) + '\n';
        
		// Write temperature information
		str += "TRANGE\n 300 \t 2000 \t 10 \n";
        //double temp = rxnSystem.getPresentTemperature().getK();
        //str += "1\t" + Double.toString(temp) + '\n';
        
		// Write pressure information
		str += "PRANGE\n 0.01 \t 100 \t 10 \n";
        str += "CHEBYSHEV \n 7 \t 4 \n";
        //double pres = rxnSystem.getPresentPressure().getAtm();
        //str += "1\t" + Double.toString(pres) + '\n';
        
        if (entryIsomer.isMultimolecular()) {
			
        	// Write chemically-activated header
			str += "CHEMACT\n";
        	str += "INPUT\n";
        	
			// Get entry reaction for network
			if (entryReaction == null) 
				throw new NullPointerException();
			
			// Get kinetics of entry reaction
        	Kinetics k;
        	if (entryReaction.isForward()) {
        		k = entryReaction.getKinetics();
        	}
        	else {
        		k = entryReaction.getFittedReverseKinetics();
        	}
        	if (k == null) 
				throw new NullPointerException();
        
        	// Write kinetics of entry reaction
			str += Double.toString(k.getAValue()) + '\t';
        	str += Double.toString(k.getNValue()) + '\t';
        	str += "0.0\t";
        	str += Double.toString(k.getEValue()) + '\n';
        }
        else if (entryIsomer.isUnimolecular()) {
        	// Write dissociation header
			str += "DISSOC\n";
        	str += "INPWONLY\n";
        	str += "SPC" + Integer.toString(entryIsomer.getSpecies(0).getID()) + '\n';
        }
		else throw new Exception("Parameter 'entryIsomer' is invalid.");
        
        // Write entry mass
		str += "MASS\n";
        str += MathTool.formatDouble(entryIsomer.getMolecularWeight(), 10, 2) + '\n';
        
		// Write Lennard-Jones parameters
		str += "PARAMETERS\n";
        LennardJones lj = pdn.getUniIsomers().get(0).getSpecies(0).getLJ();
        str += MathTool.formatDouble(lj.getSigma(), 10, 2) + '\t' + MathTool.formatDouble(lj.getEpsilon(), 10, 2) + '\n';
        
		// Write mean change in energy for deactivating collision
		str += "DEDOWN\n";
        str += "INT\n";
        str += " 0.20\n";
		
		// Write ?
        str += "BSGS\n";
        str += " 5.0\n";
        str += "XMG\n";
        
        // Determine bath gas concentration (i.e. total concentration of colliders)
		double totalConc = 0;
        HashMap colliders = rxnSystem.identifyColliders();
        for (Iterator iter = colliders.values().iterator(); iter.hasNext();) {
        	totalConc += ((Double) iter.next()).doubleValue();
        }
        
		// Write information about individual colliders
		double dEdown = 0;
        for (Iterator iter = colliders.keySet().iterator(); iter.hasNext();) {
        	
			Object key = iter.next();
            str += "COLLIDER\n";
        	if (key instanceof Species) {
        		Species spe = (Species) key;
        		str += "!" + spe.getName() + '\n';
        		double conc = ((Double) colliders.get(spe)).doubleValue();
        		double mf = conc/totalConc;
        		str += Double.toString(mf) + '\t' + Double.toString(spe.getMolecularWeight()) + '\t';
        		lj = spe.getLJ();
        		str += Double.toString(lj.getSigma()) + '\t' + Double.toString(lj.getEpsilon()) + '\t';
        		dEdown = spe.getDeltaEDown();
        		if (dEdown == 0) {
        			System.out.println("unknown colliders's dEdown: " + spe.getName());
        			System.exit(0);
        		}
        		str += Double.toString(dEdown) + '\n';
        	}
        	else if (key instanceof String) {
        		String name = (String)key;
        		double MW = 0.0;
        		if (name.equals("Ar") || name.equals("AR")) {
        			lj = new LennardJones();
        			dEdown = 374.0;
        			MW = 39.95;
        		}
        		else if (name.equals("N2")) {
        			lj = new LennardJones();
        			dEdown = 461.0;
        			MW = 28.01;
        		}
        		else if (name.equals("He") || name.equals("HE")) {
        			lj = new LennardJones();
        			dEdown = 291.0;
        			MW = 4.00;
        		}
        		else {
        			System.out.println("unknown colliders: " + name);
        			System.exit(0);
        		}
        		str += "!" + name + '\n';
        		double conc = ((Double)colliders.get(name)).doubleValue();
        		double mf = conc/totalConc;
        		str += Double.toString(mf) + '\t' + Double.toString(MW) + '\t';
        		str += Double.toString(lj.getSigma()) + '\t' + Double.toString(lj.getEpsilon()) + '\t';
        		str += Double.toString(dEdown) + '\n';
        	}
        	else {
				System.out.println("unknown colliders: " + key.toString());
				System.exit(0);
        	}
        }
        
        return str;
    }
	
	/**
	 * Parses a Chemdis output file and updates the reaction network and system
	 * accordingly.
	 * @param pdn The pressure-dependent reaction network of interest
	 * @param rxnSystem The reaction system of interest
	 */
	public LinkedList<PDepReaction> readOutputFile(PDepNetwork pdn, 
			ReactionSystem rxnSystem, PDepIsomer entryIsomer) {
        
		LinkedList<PDepReaction> reactionList = new LinkedList<PDepReaction>();
			
        try {
        	String dir = System.getProperty("RMG.workingDirectory");
        	String chemdis_output = "chemdis/chemdis-rmg.out";
        
        	FileReader in = new FileReader(chemdis_output);
        	BufferedReader data = new BufferedReader(in);
        
        	String line = ChemParser.readMeaningfulLine(data);
        	line = ChemParser.readMeaningfulLine(data);
        	line = line.trim();
        	int rNum = 0;
        	if (line.startsWith("CHEMACT")) {
        		rNum = 2;
        	}                        
        	else if (line.startsWith("DISSOC")) {
        	  	rNum = 1;
        	}
        	else {
        		System.out.println("Wrong output from chemdis: unknown type!");
        		System.out.println("Unknown key word for PDep Network: " + line);
        		System.exit(0);  	
        	}
        	LinkedList reactant = new LinkedList();
        	StringTokenizer st = new StringTokenizer(line);
        	String type = st.nextToken();
        	String temp = st.nextToken();
        	temp = st.nextToken();
        	temp = st.nextToken();
        	String r1 = st.nextToken().trim();
        	r1 = r1.substring(3, r1.length());
        	int idr1 = Integer.parseInt(r1);
        	Species sr1 = SpeciesDictionary.getInstance().getSpeciesFromID(idr1);
        	String newName = sr1.getName()+"("+String.valueOf(sr1.getID())+")";
        	reactant.add(sr1);
        	if (rNum == 2) {
        		temp = st.nextToken();
        		String r2 = st.nextToken().trim();
        		r2 = r2.substring(3, r2.length());
        		int idr2 = Integer.parseInt(r2);
        		Species sr2 = SpeciesDictionary.getInstance().getSpeciesFromID(idr2); 
        		newName += "+" + sr2.getName()+"("+String.valueOf(sr2.getID())+")";
        		reactant.add(sr2);
        	}
        	
        	double Tmax=0;
        	double Tmin=0;
        	double Pmax=0;
        	double Pmin=0;
        	
        	int nT = 7; 
        	int nP = 4;
        	
        	line = ChemParser.readMeaningfulLine(data);
        	if (line.startsWith("Temperature range")) {
        		line = ChemParser.readMeaningfulLine(data);
        		st = new StringTokenizer(line);
        		String tL = st.nextToken().trim();
        		String tH = st.nextToken().trim();
        		Tmin = Double.parseDouble(tL);
        		Tmax = Double.parseDouble(tH);
        	}
        	else {
        		System.out.println("Can't read T range from chemdis output file!");
        		System.exit(0);  	
        	}
                                                 
        	line = ChemParser.readMeaningfulLine(data);
        	if (line.startsWith("Pressure range")) {
        		line = ChemParser.readMeaningfulLine(data);
        		st = new StringTokenizer(line);
        		String pL = st.nextToken().trim();
        		String pH = st.nextToken().trim();
        		Pmin = Double.parseDouble(pL);
        		Pmax = Double.parseDouble(pH);
        	}
        	else {
        		System.out.println("Can't read P range from chemdis output file!");
        		System.exit(0);  	
        	}
        	
        	line = ChemParser.readMeaningfulLine(data);
        	while (!line.startsWith("END")) {
        		line = line.trim();
        		LinkedList product = new LinkedList();
        		st = new StringTokenizer(line);
        		String rxntype = st.nextToken();
        		int pNum = 1;
        		String p1 = st.nextToken().trim();
        		p1 = p1.substring(3, p1.length());
        		int idp1 = Integer.parseInt(p1);
				SpeciesDictionary.getSpeciesFromID(idp1);
				Species sp1 = SpeciesDictionary.getInstance().getSpeciesFromID(idp1);
        	    product.add(sp1);
        		if (st.hasMoreTokens()) {
        			String next = st.nextToken();
         			if ((next.trim()).equals("+")) {	
        				String p2 = st.nextToken().trim();
        				p2 = p2.substring(3, p2.length());
        				int idp2 = Integer.parseInt(p2);
        				Species sp2 = SpeciesDictionary.getInstance().getSpeciesFromID(idp2);
        				product.add(sp2);
        				pNum++;
        			}
        		}
				PDepIsomer productIsomer = null;
				if (product.size() == 1) {
					for (int i = 0; i < pdn.getUniIsomers().size(); i++) {
						if (pdn.getUniIsomers().get(i).getSpeciesList().equals(product))
							productIsomer = pdn.getUniIsomers().get(i);
					}
				}
				else if (product.size() > 1) {
					for (int n = 0; n < pdn.getMultiIsomers().size(); n++) {
						if (pdn.getMultiIsomers().get(n).getSpeciesList().equals(product))
							productIsomer = pdn.getMultiIsomers().get(n);
					}
				}
				if (productIsomer == null) throw new NullPointerException();
					
        		// read chebyshev polynomial
        		double [][] alpha = new double[nT][nP];
        		for (int i = 0; i < nT; i++) {
        			line = ChemParser.readMeaningfulLine(data);
        			st = new StringTokenizer(line);
        			for (int j = 0; j < nP; j++) {
        				String a = st.nextToken().trim();
        				alpha[i][j] = Double.parseDouble(a);
        			}
        		}
        		
        		Temperature tLow = new Temperature(Tmin, "K");
        		Temperature tHigh = new Temperature(Tmax, "K");
        		Pressure pLow = new Pressure(Pmin, "Atm");
        		Pressure pHigh = new Pressure(Pmax, "Atm");
        		ChebyshevPolynomials cp = new ChebyshevPolynomials(nT, tLow, tHigh, nP, pLow, pHigh, alpha);
        		
        		PDepReaction rxn = new PDepReaction(entryIsomer, productIsomer, cp);
        		reactionList.add(rxn);
				
        		line = ChemParser.readMeaningfulLine(data);
        		
        	}
        	in.close();
			
			File f = new File("chemdis/fort.10");
        	File newFile = new File("chemdis/"+newName+"_input");
        	f.renameTo(newFile);
        	f = new File(chemdis_output);
        	newFile = new File("chemdis/"+newName+"_output");
        	f.renameTo(newFile);
        }
        catch (Exception e) {
        	System.out.println("Wrong output from chemdis!");
        	System.out.println(e.getMessage());
        	System.exit(0);
        }
        
		return reactionList;
    }
}
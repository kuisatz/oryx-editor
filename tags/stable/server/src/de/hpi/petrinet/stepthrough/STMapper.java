package de.hpi.petrinet.stepthrough;

import java.util.ArrayList;
import java.util.List;

import de.hpi.bpmn.DiagramObject;
import de.hpi.bpmn.Edge;
import de.hpi.bpmn.SubProcess;
import de.hpi.bpmn.XORDataBasedGateway;
import de.hpi.highpetrinet.HighPetriNet;
import de.hpi.highpetrinet.verification.HighPNInterpreter;
import de.hpi.petrinet.Marking;
import de.hpi.petrinet.Transition;

public class STMapper {
	// The PetriNet
	private HighPetriNet petriNet;
	// ... with a fitting interpreter
	private HighPNInterpreter petriNetInterpreter;
	// ... and its marking
	private Marking petriNetMarking;
	// A list of transitions that have changed because an object has been fired
	private List<STTransition> changedTransitions;
	// A list of XOR split gateways where one of their edges have been fired
	private List<DiagramObject> changedXORSplits;
	// The AutoSwitchLevel that is set
	private AutoSwitchLevel switchLevel = AutoSwitchLevel.SemiAuto;
	
	public STMapper(HighPetriNet pn) {
		petriNet = pn;
		// Get an interpreter
		petriNetInterpreter = new HighPNInterpreter();
		// Start with the initial marking
		petriNetMarking = petriNet.getInitialMarking();

		fireInvisibleTransitions(); // invisible start events may have been generated
		changedTransitions = getFireableTransitions();
		
		changedXORSplits = new ArrayList<DiagramObject>();
	}
	
	public void setAutoSwitchLevel(AutoSwitchLevel level) {
		this.switchLevel = level;
	}

	public List<DiagramObject> getFireableObjects () {
		List<DiagramObject> objects = new ArrayList<DiagramObject>();
		List<Transition> transitionList = petriNetInterpreter.getEnabledTransitions(petriNet, petriNetMarking);
		STTransition t;
		
		for(int i = 0; i < transitionList.size(); i++) {
			t = (STTransition)transitionList.get(i);
			objects.add(t.getBPMNObj());
		}
		
		return objects;
	}
	
	private List<STTransition> getFireableTransitions () {
		List<STTransition> transitions = new ArrayList<STTransition>();
		List<Transition> transitionList = petriNetInterpreter.getEnabledTransitions(petriNet, petriNetMarking);
		
		for(int i = 0; i < transitionList.size(); i++) {
			transitions.add((STTransition)transitionList.get(i));
		}
		
		return transitions;
	}
	
	private void fireInvisibleTransitions() {
		// Invisible transitions exist because the preprocessor added a bpmn object that the user can't see in Oryx
		// The resulting transitions have no resourceId set and can't be fired by the user
		List<STTransition> fireableObjects;
		boolean transitionFired = true;
		
		while(transitionFired)
		{
			transitionFired = false;
			fireableObjects = getFireableTransitions();

			for(int i = 0; i < fireableObjects.size(); i++) {
				// If no resourceId is set, there is no visible object in the Oryx diagram
				DiagramObject obj = fireableObjects.get(i).getBPMNObj();
				if(obj != null && obj.getResourceId() == null) {
					STTransition stt = fireableObjects.get(i);
					petriNetMarking = petriNetInterpreter.fireTransition(petriNet, petriNetMarking, stt);
					stt.incTimesExecuted();
					transitionFired = true;
					break;
				}
			}
		}
	}
	
	private void fireTransition(STTransition t) {
		// Fire transition
		petriNetMarking = petriNetInterpreter.fireTransition(petriNet, petriNetMarking, t);
		// Increase the counter
		t.incTimesExecuted();
		
		// Extra check to catch XOR gateway splits in order to highlight them
		if(t.getBPMNObj() instanceof Edge) {
			Edge edge = (Edge)t.getBPMNObj();
			DiagramObject source = edge.getSource();
			if (source instanceof XORDataBasedGateway) {
				changedXORSplits.add(source);
			}
		}
	}
	
	public boolean fireObject(String objResourceID) {
		STTransition t = null;
		// Check if this object can be fired
		List<STTransition> fireableObjects = getFireableTransitions();
		boolean isFireable = false;
		for(int i = 0; i < fireableObjects.size(); i++) {
			if (fireableObjects.get(i).getBPMNObj().getResourceId() != null) { // Otherwise there is a null-pointer exception when checking an invisble object
				if (fireableObjects.get(i).getBPMNObj().getResourceId().equals(objResourceID)) {
					t = fireableObjects.get(i);
					isFireable = true;
					break;
				}
			}
		}
		
		if(!isFireable) {
			return false;
		}
		
		// Fire transition
		fireTransition(t);
		
		// Add obj to list because timesExecuted has changed
		changedTransitions.add(t);
		
		// Fire invisible transitions
		fireInvisibleTransitions();
		
		// Auto fire
		AutoSwitchLevel currentLevel = switchLevel;
		// Only fire transitions that are above NoAuto
		while (currentLevel != AutoSwitchLevel.NoAuto) {
			boolean transitionFired = true;
			while (transitionFired) {
				transitionFired = false;

				List<STTransition> nowFireableObjects = getFireableTransitions();

				for (int i = 0; i < nowFireableObjects.size(); i++) {
					STTransition stt = nowFireableObjects.get(i);
					// Transition on the current level?
					if (stt.getAutoSwitchLevel().equals(currentLevel)) {
						// Transition wasn't enabled before?
						if (!fireableObjects.contains(stt)) {
							fireTransition(stt);
							changedTransitions.add(stt);
							transitionFired = true;
							// There should only be one transition enabled that wasn't enabled before on this level
							// So let's break and see if a new one has been enabled
							fireInvisibleTransitions();
							break;
						}
					}
				}
			}
			// Continue checking transitions on a lower level
			if (currentLevel.lowerLevelExists())
				currentLevel = currentLevel.lowerLevel();
		}

		List<STTransition> newlyFireableObjects = getFireableTransitions();
		// Update changedObjs with Objects that weren't fireable before
		for(int i = 0; i < newlyFireableObjects.size(); i++) {
			if(!fireableObjects.contains(newlyFireableObjects.get(i))) {
				changedTransitions.add(newlyFireableObjects.get(i));
			}
		}
		
		// Update changedObjs with Objects that aren't fireable anymore
		for(int i = 0; i < fireableObjects.size(); i++) {
			if(!newlyFireableObjects.contains(fireableObjects.get(i))) {
				// May already be inside the list because it has been fired
				if(!changedTransitions.contains(fireableObjects.get(i))) {
					changedTransitions.add(fireableObjects.get(i));
				}
			}
		}
		
		return true;
	}
	
	public void clearChangedObjs() {
		changedTransitions.clear();
		changedXORSplits.clear();
	}
	
//	public List<STTransition> getChangedObjs() {
//		return changedTransitions;
//	}
	
	public String getChangedObjsAsString() {
		StringBuilder sb = new StringBuilder(15 * changedTransitions.size());
		List<STTransition> fireableObjects = getFireableTransitions();
		// Save relevant information in the string
		// Format: BPMNResourceID,TimesExecuted,isFireable;
		
		// Start with the transitions
		for(STTransition t : changedTransitions) {
			// Don't send invisible objects
			if (t.getBPMNObj().getResourceId() != null) {
				// Non-empty subprocesses should only be highlighted if the user has to fire them
				if (t.getBPMNObj() instanceof SubProcess) {
					SubProcess sp = (SubProcess) t.getBPMNObj();
					if((sp.getChildNodes().size() > 0) && !fireableObjects.contains(t))
						continue;
				}
				sb.append(t.getBPMNObj().getResourceId());
				sb.append(",");
				sb.append(t.getTimesExecuted());
				sb.append(",");
				if (fireableObjects.contains(t))
					sb.append("t;");
				else
					sb.append("f;");
			}
		}
		// XOR Splits
		for(DiagramObject d : changedXORSplits) {
			sb.append(d.getResourceId());
			// TimesExecuted is not calculated for these
			sb.append(",-1,f;");
		}
		
		return sb.toString();
	}
	
}

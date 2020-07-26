// myALG2

package FinalProject.BL.Agents;
import FinalProject.Utils;
import jade.lang.acl.ACLMessage;
import org.apache.log4j.Logger;

import java.sql.Array;
import java.util.*;

import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateEPeak;
import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateTotalConsumptionWithPenalty;

public class HouseAr extends SmartHomeAgentBehaviour {

    private final static Logger logger = Logger.getLogger(House.class);
    private int[] ticksBag;
    private boolean inImprovementRound = false;
    private double oldPrice;

    public HouseAr() {
        super();
    }


    //==================================================================================================================
    //==================================================================================================================
    @Override
    protected void doIteration() {
        if (agent.isZEROIteration()) {
            initMsgTemplate(); // needs to be here to make sure SmartHomeAgent class is init
            buildScheduleFromScratch();
            agent.setZEROIteration(false);
            agent.setPriceSum(calcCsum(iterationPowerConsumption));
        } else {
            logger.info("doIteration!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            receiveNeighboursIterDataAndHandleIt();
            improveSchedule(); // here is the MAGIC

        }
        beforeIterationIsDone();
        this.currentNumberOfIter++;
    }

    private void improveSchedule() {
        helper.resetProperties();
        buildScheduleBasic(false);
        List<double[]> allScheds = getNeighbourScheds();
        // ==============================================

        //calculate previous iteration grade:
        //create a map holding the ticks worked in the previous iteration by each property
        Map<PropertyWithData, Set<Integer>> prevSchedForAllProps = new HashMap<>(propToSubsetsMap.size());
        propToSubsetsMap.keySet().forEach(prop -> {
            Set<Integer> prevTicks = new HashSet<>(getTicksForProp(prop));
            prevSchedForAllProps.put(prop, prevTicks);
        });

        //we need to copy the array because we need to use it for calculating the new sched as well
        double[] prevSched = helper.cloneArray(iterationPowerConsumption); //already with background load!
        prevSchedForAllProps.forEach((prop, ticks) -> {
            double powerCons = prop.getPowerConsumedInWork();
            ticks.forEach(tick -> prevSched[tick] += powerCons);
        });
        allScheds.add(prevSched);
        double prevGrade = calcImproveOptionGrade(prevSched, allScheds); //the grade for the previous iteration

        //calculate a new random schedule (similar to above):
        allScheds.remove(prevSched); //we want to use the same list later, clean it
        Map<PropertyWithData, Set<Integer>> randomSchedForAllProps = new HashMap<>(propToSubsetsMap.size());
        //for each property, pick a random set of ticks (satisfying all constraints)
        propToSubsetsMap.keySet().forEach(prop -> {
            Set<Integer> randSubset = pickRandomSubsetForProp(prop);
            randomSchedForAllProps.put(prop, randSubset);
        });
        double[] randSched = helper.cloneArray(iterationPowerConsumption); //already with background load!
        randomSchedForAllProps.forEach((prop, ticks) -> {
            double powerCons = prop.getPowerConsumedInWork();
            ticks.forEach(tick -> randSched[tick] += powerCons);
        });
        allScheds.add(randSched);
        double newGrade = calcImproveOptionGrade(randSched, allScheds); //the grade for this iteration

        //flipCoin(0.8f) - probability function, returns TRUE with a given prob otherwise FALSE

        //decide which of the 2 schedules to pick:
        if (newGrade < prevGrade) { //pick the new schedule
            helper.totalPriceConsumption = newGrade;
            helper.ePeak = calculateEPeak(allScheds);
            randomSchedForAllProps.forEach((prop, ticks) ->
                    updateTotals(prop,new ArrayList<>(ticks), propToSensorsToChargeMap.get(prop)));
        }
        else { //pick the previous schedule
            helper.totalPriceConsumption = prevGrade;
            allScheds.remove(randSched);
            allScheds.add(prevSched);
            helper.ePeak = calculateEPeak(allScheds);
            prevSchedForAllProps.forEach((prop, ticks) ->
                    updateTotals(prop,new ArrayList<>(ticks), propToSensorsToChargeMap.get(prop)));
        }


    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private Set<Integer> pickRandomSubsetForProp(PropertyWithData prop) {
        List<Set<Integer>> allSubsets = propToSubsetsMap.get(prop);
        if (allSubsets == null || allSubsets.isEmpty()) {
            return new HashSet<>(0);
        }
        int index = drawRandomNum(0, allSubsets.size() - 1);
        return allSubsets.get(index);
    }

    private Set<Integer> smartChoiceForProp(PropertyWithData prop) {
        List<Set<Integer>> allSubsets = propToSubsetsMap.get(prop);
        if (allSubsets == null || allSubsets.isEmpty()) {
            return new HashSet<>(0);
        }
        int index = drawRandomNum(0, allSubsets.size() - 1);
        return allSubsets.get(index);
    }


    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    //==================================================================================================================
    //==================================================================================================================
    @Override
    protected void generateScheduleForProp(PropertyWithData prop, double ticksToWork,
                                           Map<String, Integer> sensorsToCharge, boolean randomSched) {
        //iteration 0, build a schedule for prop
        if (agent.isZEROIteration()) {
            startWorkZERO(prop, sensorsToCharge, ticksToWork);
        }
        //non-zero iteration, just fill propToSubsetsMap if not already filled.
        //the schedule for all of the properties together will be built later in improveSchedule
        else {
            if (!propToSubsetsMap.containsKey(prop)) {
                getSubsetsForProp(prop, ticksToWork); //to put in map if absent
            }
        }
    }
    //==================================================================================================================
    //==================================================================================================================
    @Override
    protected void onTermination() {
        logger.info("onTermination!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        logger.info(agent.getName() + " for problem " + agent.getProblemId() + "and algo House is TERMINATING!");
    }

    //==================================================================================================================
    //==================================================================================================================
    @Override
    public House cloneBehaviour() {
        logger.info("cloneBehaviour!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        House newInstance = new House();
        newInstance.finished = this.finished;
        newInstance.currentNumberOfIter = this.currentNumberOfIter;
        newInstance.FINAL_TICK = this.FINAL_TICK;
        newInstance.agentIterationData = null;
        return newInstance;
    }

    //==================================================================================================================
    //==================================================================================================================
    @Override
    protected double calcImproveOptionGrade(double[] newPowerConsumption, List<double[]> allScheds) {
        logger.info("calcImproveOptionGrade!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        double price = calcCsum(newPowerConsumption);
        return price + calculateEPeak(allScheds);
    }
}


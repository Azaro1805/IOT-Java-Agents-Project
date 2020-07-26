
package FinalProject.BL.Agents;
import org.apache.log4j.Logger;

import java.util.*;

import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateEPeak;
import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateTotalConsumptionWithPenalty;

public class House extends SmartHomeAgentBehaviour  {

    private final static Logger logger = Logger.getLogger(House.class);
    private int[] ticksBag;
    private boolean inImprovementRound = false;
    private double oldPrice;

    public House() {
        super();
    }

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
        Map<PropertyWithData, Set<Integer>> Random = new HashMap<>(propToSubsetsMap.size());
        Map<PropertyWithData, Set<Integer>> randomSchedForAllProps = new HashMap<>(propToSubsetsMap.size());
        //////////////////////////////////////////////////////////////////////////////////////////////
        double newGrade;
        double minGrade =  100000; //very big num
        double[] bestSched = null; //for min will find
        for (int i = 0; i < 10; i++) { //10 random solutions iterations
            randomSchedForAllProps.clear(); //clean
            propToSubsetsMap.keySet().forEach(prop -> { //random set of ticks for each property
                Set<Integer> randSubset = pickRandomSubsetForProp(prop);
                randomSchedForAllProps.put(prop, randSubset); //add to all scheds
            });
            double[] randSched = helper.cloneArray(iterationPowerConsumption); //already with background load!
            randomSchedForAllProps.forEach((prop, ticks) -> {
                double powerCons = prop.getPowerConsumedInWork();
                ticks.forEach(tick -> randSched[tick] += powerCons);
            });
            allScheds.add(randSched);
            newGrade = calcImproveOptionGrade(randSched, allScheds);
            if (newGrade <= minGrade){ //replace in case new grade is better
                minGrade = newGrade;
                bestSched = randSched;
                Random = randomSchedForAllProps; //update which set is the best
            }
            allScheds.remove(randSched); //clear from list
        }
        allScheds.add(bestSched);
        //flipCoin(0.8f) - probability function, returns TRUE with a given prob otherwise FALSE
        //////////////////////////////////////////////////////////////////////////////////////////////
        //decide which of the 2 schedules to pick:
        if (minGrade <= prevGrade && flipCoin(0.7f)) { //conditions to choose new schedule only both!
            helper.totalPriceConsumption = minGrade;
            helper.ePeak = calculateEPeak(allScheds);
            Random.forEach((prop, ticks) ->
                    updateTotals(prop, new ArrayList<>(ticks), propToSensorsToChargeMap.get(prop))); //update neighbours and props
        }
        else { //pick the previous schedule
            helper.totalPriceConsumption = prevGrade;
            allScheds.remove(bestSched);
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
    protected void onTermination() {
        logger.info("onTermination!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        logger.info(agent.getName() + " for problem " + agent.getProblemId() + "and algo House is TERMINATING!");
    }

    public House cloneBehaviour() {
        logger.info("cloneBehaviour!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        House newInstance = new House();
        newInstance.finished = this.finished;
        newInstance.currentNumberOfIter = this.currentNumberOfIter;
        newInstance.FINAL_TICK = this.FINAL_TICK;
        newInstance.agentIterationData = null;
        return newInstance;
    }

    protected double  calcImproveOptionGrade(double[] newPowerConsumption, List<double[]> allScheds) {
        logger.info("calcImproveOptionGrade!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        double price = calcCsum(newPowerConsumption);
        return price + calculateEPeak(allScheds);
    }
}
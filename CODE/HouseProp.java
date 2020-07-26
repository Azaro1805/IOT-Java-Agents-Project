
package FinalProject.BL.Agents;
import org.apache.log4j.Logger;
import java.util.*;
import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateEPeak;
import static FinalProject.BL.DataCollection.PowerConsumptionUtils.calculateTotalConsumptionWithPenalty;

public class HouseProp extends SmartHomeAgentBehaviour {

    private final static Logger logger = Logger.getLogger(HouseProp.class);
    private int[] ticksBag;
    private boolean inImprovementRound = false;
    private double oldPrice;

    public HouseProp() { super(); }


    //==================================================================================================================
    //==================================================================================================================
    //The main Loop
    @Override
    protected void doIteration() {
        if (agent.isZEROIteration()) {
            // actions of the very first iteration
            initMsgTemplate();
            buildScheduleFromScratch();
            agent.setZEROIteration(false);
            agent.setPriceSum(calcCsum(iterationPowerConsumption));
        } else {
            // actions during inter-houses iteration
            logger.info("");
            receiveNeighboursIterDataAndHandleIt();
            improveSchedule(); // write your code in this function
        }
        beforeIterationIsDone();
        this.currentNumberOfIter++;
    }

    //----------------------------------------YOUR CODE IN THIS FUNCTION----------------------------------------------//
    private void improveSchedule() {
        helper.resetProperties();
        buildScheduleBasic(false);
        List<double[]> allScheds = getNeighbourScheds(); // getting schedules of all neighbours
        List<PropAgent> listOfPropsAgents = new ArrayList<PropAgent>(); //list of properties helps to do inner iterations of properties
        // ==============================================
        propToSubsetsMap.keySet().forEach(prop -> {
            PropAgent agent = new PropAgent(prop, allScheds); //creating new agent representing property
            listOfPropsAgents.add(agent); // add this agent to our list
            Set<Integer> prevTicks = new HashSet<>(getTicksForProp(prop)); // get the Ticks that this property has now
            listOfPropsAgents.get(listOfPropsAgents.indexOf(agent)).setPrevTicks(prevTicks); // set this ticks to relevant agent
        });
        //listOfPropsAgents = list of all the props inside the house

        //create neighbours to each agent
        listOfPropsAgents.forEach( agent -> { //add all other agents (properties) as neibours in the house
            listOfPropsAgents.forEach( neighbour -> {
                if(!neighbour.equals(agent)){
                    agent.neighbours.add(neighbour);
                }
            });
        });

        // iteration loop of properties (here is an example of simulating distributed behaviour of agents)
        for(int i = 0; i < 100; i++){ //as demand by instructions: 100 inner iterations between props inside the house
            listOfPropsAgents.forEach( agent -> {
                agent.chooseTicks(); //each prop receives(choose) ticks
            });
            listOfPropsAgents.forEach(agent -> {
                agent.changeTicks();
            });
        }

        //after each agent made its choice of assumption (which ticks it is going to work in)
        //we will build the final chosenSched:
        double[] chosenSched = helper.cloneArray(iterationPowerConsumption); //already with background load!
        listOfPropsAgents.forEach( agent -> {
            agent.updateChosenSched(chosenSched); //each props report his final ticks
        });
        allScheds.add(chosenSched);
        double newGrade = calcImproveOptionGrade(chosenSched, allScheds); //calc consumption for iteration
        helper.totalPriceConsumption = newGrade;
        helper.ePeak = calculateEPeak(allScheds);
        // final step
        listOfPropsAgents.forEach( agent -> {
            updateTotals(agent.prop, new ArrayList<>(agent.currTicks),propToSensorsToChargeMap.get(agent.prop));
        });
    }

    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    private Set<Integer> pickRandomSubsetForProp(PropertyWithData prop) {
        List<Set<Integer>> allSubsets = propToSubsetsMap.get(prop); //all possible subsets of hours (ticks) where the property
        // can work in. These subsets made according to constrains of this particular house
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
        logger.info(agent.getName() + " for problem " + agent.getProblemId() + "and algo HouseProp is TERMINATING!");
    }

    //==================================================================================================================
    //==================================================================================================================
    @Override
    public HouseProp cloneBehaviour() {
        logger.info("cloneBehaviour!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        HouseProp newInstance = new HouseProp();
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

    // -----------------------------------------------------------------------------------------------------------------
    // -----------------------------------------------------------------------------------------------------------------
    // PROPERTY - AGENT CLASS
    // -----------------------------------------------------------------------------------------------------------------
    // -----------------------------------------------------------------------------------------------------------------
    ///inner class represents the Agent of Property
    class PropAgent {
        PropertyWithData prop;
        List<double[]> allScheds; //reference
        Set<Integer> nextTicks;
        Set<Integer> currTicks;
        double[] currSched;
        List<PropAgent> neighbours;
        boolean toChange;
        Set<Integer> bestTicks; // for inner iteration

        PropAgent(PropertyWithData prop, List<double[]> allScheds) {
            this.prop = prop;
            this.allScheds = allScheds;
            this.neighbours = new ArrayList<PropAgent>();
            this.toChange = false;
        }

        void setPrevTicks(Set<Integer> prevTicks) {
            this.currTicks = prevTicks;
        }

        void updateChosenSched(double[] chosenSched) {
            double powerCons = prop.getPowerConsumedInWork();
            this.currTicks.forEach(tick -> {
                chosenSched[tick] += powerCons;
            });
        }

        void chooseTicks() {

            double oldGrade = 100000; //will replace first
            double minGrade = oldGrade; //at the beginning
            double newGrade;

            this.currSched = helper.cloneArray(iterationPowerConsumption);
                //already with background load! The background here
                // is additional amount of energy spent by house in this tick - do not forget to create the new schedule that way
                // in order to not loose this information
            this.nextTicks = pickRandomSubsetForProp(this.prop); // in this case we choose the random set of ticks
                //see pickRandomSubsetForProp() in order to understand how it choose the set. You also can play with it

            neighbours.forEach(neighbour -> {// insert ticks of neighbours
                neighbour.currTicks.forEach(tick -> {
                    this.currSched[tick] += neighbour.prop.getPowerConsumedInWork();
                    // every prop has a method getPowerConsumedInWork() - it returns a value in energy units, how much it
                    // consumes in one hour (in one tick)
                });
            });
            // insert my curr ticks
            this.currTicks.forEach(tick -> {
                this.currSched[tick] += this.prop.getPowerConsumedInWork();
            });
            // calc the grade including the agent conterbution
            this.allScheds.add(this.currSched);
            oldGrade = calcImproveOptionGrade(this.currSched, this.allScheds);
            this.allScheds.remove(this.currSched); // in order to calculate another assumption in a proper way
                //we replace this currSched from allScheds
                //new one
            this.currSched = helper.cloneArray(iterationPowerConsumption);
            neighbours.forEach(neighbour -> { // insert ticks of neighbours
                neighbour.currTicks.forEach(tick -> {
                    this.currSched[tick] += neighbour.prop.getPowerConsumedInWork();
                });
            });
            // insert my new ticks
            this.nextTicks.forEach(tick -> {
                this.currSched[tick] += this.prop.getPowerConsumedInWork();
            });
            // calc the grade
            this.allScheds.add(this.currSched); //insert new Scheds so will calc include it
            newGrade = calcImproveOptionGrade(this.currSched, this.allScheds); //grade for current iter
            this.allScheds.remove(this.currSched); //for next iteration
        
            if (newGrade < minGrade){
                minGrade = newGrade; //update best grade yet
                bestTicks = nextTicks; //update best ticks yet
            }

            // decision: we compare both assignments using their grades
            // you can use flipCoin(0.7f) as a boolean propability function
            if (minGrade < oldGrade && flipCoin(0.7f)) {
                this.toChange = true;
            }
        }
        void changeTicks() {
            if (this.toChange) {
                this.currTicks = this.bestTicks;
                this.toChange = false;
            }
        }
    }
}

package mekhq.campaign.personnel.education;

import mekhq.MekHQ;
import mekhq.campaign.Campaign;
import mekhq.campaign.CampaignOptions;
import mekhq.campaign.force.CombatTeam;
import mekhq.campaign.force.Force;
import mekhq.campaign.mission.AtBContract;
import mekhq.campaign.personnel.Person;
import mekhq.campaign.personnel.Skill;
import mekhq.campaign.unit.Unit;

import java.time.LocalDate;
import java.util.*;

import static java.lang.Math.round;
import static mekhq.campaign.personnel.SkillType.EXP_GREEN;
import static mekhq.utilities.ReportingUtilities.CLOSING_SPAN_TAG;
import static mekhq.utilities.ReportingUtilities.spanOpeningWithCustomColor;

/**
 * Handles the training of combat teams within the campaign.
 *
 * <p>The class focuses on managing the process of skill improvement and education for combat teams
 * and their associated personnel. It identifies eligible combat teams that are currently performing
 * training, validates their contracts, and processes training for each team member according to their
 * roles and skills.</p>
 *
 * <p>Key Methods:
 * <ul>
 *     <li>{@link #processTrainingCombatTeams(Campaign)}: The main entry point for processing all
 *     combat team training.</li>
 *     <li>{@link #processTraining(Campaign, CombatTeam)}: Handles the detailed implementation of
 *     training logic for individual combat teams.</li>
 * </ul>
 */
public class TrainingCombatTeams {
    private static final String BUNDLE_NAME = "mekhq.resources.Education";
    private static ResourceBundle resources = ResourceBundle.getBundle(BUNDLE_NAME, MekHQ.getMHQOptions().getLocale());

    /**
     * Processes all training combat teams in the campaign.
     *
     * <p>This method iterates through all combat teams in the campaign and processes training
     * for those whose role includes training. It validates combat team contracts to ensure
     * they are active and eligible for training on the current date. If a campaign uses StratCon,
     * it also checks that the combat team is deployed in a Sector.</p>
     *
     * @param campaign the {@link Campaign} instance managing the combat teams and their operations
     */
    public static void processTrainingCombatTeams(final Campaign campaign) {
        final LocalDate today = campaign.getLocalDate();
        final List<CombatTeam> combatTeams = campaign.getAllCombatTeams();

        for (CombatTeam combatTeam : combatTeams) {
            if (!combatTeam.getRole().isTraining()) {
                continue;
            }

            AtBContract contract = combatTeam.getContract(campaign);
            if (contract == null || !contract.isActiveOn(today, true)) {
                continue;
            }

            if (campaign.getCampaignOptions().isUseStratCon()
                && !contract.getStratconCampaignState().isForceDeployedHere(combatTeam.getForceId())) {
                continue;
            }

            processTraining(campaign, combatTeam);
        }
    }

    /**
     * Handles the training progression for an individual combat team.
     *
     * <p>This method identifies the combat team's commander, gathers their list of skills,
     * and uses them to train other team members. It determines which skills can be improved
     * by comparing the commander's skill levels to those of the trainees. Training is simulated
     * by incrementing the trainee's education time and potentially improving their skill levels.</p>
     *
     * <p>Training progression is governed by these rules:
     * <ul>
     *     <li>The commander must have skills at least one step higher than the skill they are
     *     training in their trainees.</li>
     *     <li>Trainees with a skill level above the "Green" experience level are not eligible
     *     for training in that skill.</li>
     *     <li>Skills with a level lower than the commander's skill (minus one) are eligible for training.</li>
     *     <li>The amount of education time determines when a skill is improved, based on the
     *     experience level being gained.</li>
     * </ul>
     *
     * <p>If the commander has no eligible skills to teach or the trainee has no eligible skills to
     * improve, a negative report is generated. Otherwise, successful training is logged, along
     * with any skill improvements.</p>
     *
     * <p>Education time is tracked weekly, and skill improvements occur when sufficient education
     * time has been accrued.</p>
     *
     * @param campaign   the {@link Campaign} managing the combat team and its associated personnel
     * @param combatTeam the {@link CombatTeam} undergoing training in this session
     */
    private static void processTraining(final Campaign campaign, final CombatTeam combatTeam) {
        final CampaignOptions campaignOptions = campaign.getCampaignOptions();
        final String campaignName = campaign.getName();

        // First, identify the Combat Team's commander
        Person commander = combatTeam.getCommander(campaign);

        // Then build a list of their skills
        List<String> professionSkills = new ArrayList<>();

        if (commander.getPrimaryRole().isCombat()) {
            professionSkills.addAll(commander.getProfessionSkills(campaign, false));
        }

        if (commander.getSecondaryRole().isCombat()) {
            professionSkills.addAll(commander.getProfessionSkills(campaign, true));
        }

        Map<String, Integer> commanderSkills = new HashMap<>();

        for (String professionSkill : professionSkills) {
            Skill skill = commander.getSkill(professionSkill);

            if (skill != null) {
                commanderSkills.put(professionSkill, skill.getExperienceLevel());
            }
        }

        // Next cycle through each character in the force
        Force force = campaign.getForceFor(commander);

        for (UUID unitId : force.getAllUnits(true)) {
            Unit unit = campaign.getUnit(unitId);

            if (unit == null) {
                continue;
            }

            for (Person trainee : unit.getActiveCrew()) {
                if (trainee.equals(commander)) {
                    continue;
                }

                List<Skill> skillsBeingTrained = new ArrayList<>();
                for (String commanderSkill : commanderSkills.keySet()) {
                    Skill traineeSkill = trainee.getSkill(commanderSkill);

                    if (traineeSkill != null) {
                        int traineeExperienceLevel = traineeSkill.getExperienceLevel();

                        if (traineeExperienceLevel > EXP_GREEN) {
                            continue;
                        }

                        // The commander is required to be one step above the experience level they
                        // are teaching.
                        if (traineeExperienceLevel < (commanderSkills.get(commanderSkill) - 1)) {
                            skillsBeingTrained.add(traineeSkill);
                        }
                    }
                }

                if (commanderSkills.isEmpty()) {
                    campaign.addReport(String.format(resources.getString("notLearningAnything.text"),
                        trainee.getHyperlinkedFullTitle(), commander.getFullTitle(),
                        spanOpeningWithCustomColor(MekHQ.getMHQOptions().getFontColorNegativeHexColor()),
                        CLOSING_SPAN_TAG));
                    continue;
                }

                // We piggyback on the education module here.
                // If the character ever enters actual education, this will be overwritten.
                if (!Objects.equals(trainee.getEduAcademyName(), campaignName)) {
                    trainee.setEduAcademyName(campaignName);
                    trainee.setEduEducationTime(0);
                } else {
                    int newEducationTime = trainee.getEduEducationTime() + 7;
                    trainee.setEduEducationTime(newEducationTime);

                    // The lowest skill is improved first
                    skillsBeingTrained.sort(Comparator.comparingInt(Skill::getExperienceLevel));
                    Skill targetSkill = skillsBeingTrained.get(0);
                    // The +1 is to account for the next experience level to be gained
                    int currentExperienceLevel = targetSkill.getExperienceLevel() + 1;

                    int perExperienceLevelMultiplier = 28;
                    double experienceMultiplier = campaignOptions.getXpCostMultiplier();
                    double intelligenceCostMultiplier = trainee.getIntelligenceXpCostMultiplier(campaignOptions);

                    perExperienceLevelMultiplier = (int) round(perExperienceLevelMultiplier * experienceMultiplier * intelligenceCostMultiplier);

                    if (newEducationTime >= (currentExperienceLevel * perExperienceLevelMultiplier)) {
                        trainee.setEduEducationTime(0);
                        targetSkill.setLevel(targetSkill.getLevel() + 1);


                        campaign.addReport(String.format(resources.getString("learnedNewSkill.text"),
                            commander.getFullTitle(), trainee.getHyperlinkedFullTitle(),
                            spanOpeningWithCustomColor(MekHQ.getMHQOptions().getFontColorPositiveHexColor()),
                            CLOSING_SPAN_TAG, targetSkill.getType().getName(), targetSkill.getFinalSkillValue()));
                    }
                }
            }
        }
    }
}
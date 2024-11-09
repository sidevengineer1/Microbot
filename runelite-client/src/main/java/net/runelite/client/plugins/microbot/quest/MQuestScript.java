package net.runelite.client.plugins.microbot.quest;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Random;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.questhelper.QuestHelperPlugin;
import net.runelite.client.plugins.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.questhelper.requirements.Requirement;
import net.runelite.client.plugins.questhelper.requirements.item.ItemRequirement;
import net.runelite.client.plugins.questhelper.steps.*;
import net.runelite.client.plugins.questhelper.steps.widget.WidgetHighlight;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MQuestScript extends Script {
    public static double version = 0.3;


    public static List<ItemRequirement> itemRequirements = new ArrayList<>();

    public static List<ItemRequirement> itemsMissing = new ArrayList<>();
    public static List<ItemRequirement> grandExchangeItems = new ArrayList<>();

    boolean unreachableTarget = false;
    int unreachableTargetCheckDist = 1;

    private MQuestConfig config;
    private static ArrayList<NPC> npcsHandled = new ArrayList<>();
    private static ArrayList<TileObject> objectsHandeled = new ArrayList<>();

    private final Map<Class<? extends QuestStep>, Function<QuestStep, Boolean>> stepHandlers = Map.of(
        ObjectStep.class, this::applyObjectStep,
        NpcStep.class, this::applyNpcStep,
        WidgetStep.class, this::applyWidgetStep,
        DigStep.class, this::applyDigStep,
        PuzzleStep.class, this::applyPuzzleStep,
        DetailedQuestStep.class, this::applyDetailedQuestStep
    );

    QuestStep dialogueStartedStep = null;

    public boolean run(MQuestConfig config) {
        this.config = config;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(this::executeScript, 0, Random.random(400, 1000), TimeUnit.MILLISECONDS);
        return true;
    }


    private void executeScript() {
        try {
            if (!Microbot.isLoggedIn() || !super.run() || getQuestHelperPlugin().getSelectedQuest() == null) return;

            handlePlayerActions();
            QuestStep questStep = getCurrentQuestStep();

            if (dialogueStartedStep == null && Rs2Dialogue.isInDialogue()) dialogueStartedStep = questStep;
            if (questStep == null) return;

            if (isInDialogOptions()) handleDialogOptions(questStep);
            else if (!questStep.getWidgetsToHighlight().isEmpty()) highlightWidget(questStep);

            if (isQuestInProgress()) performQuestActions(questStep);
        } catch (Exception ex) {
            ex.printStackTrace(System.out);
        }
    }

    private void performQuestActions(QuestStep questStep) {
        if (!(questStep instanceof DetailedQuestStep)) {
            stepHandlers.getOrDefault(questStep.getClass(), s -> true).apply(questStep);
            return
        }

        if (handleRequirements((DetailedQuestStep) questStep)) return;
        if (!(questStep instanceof NpcStep || questStep instanceof ObjectStep || questStep instanceof DigStep)) {
            applyDetailedQuestStep((DetailedQuestStep) questStep);
            return;
        }
    }


    private boolean isQuestInProgress() {
        return getQuestHelperPlugin().getSelectedQuest() != null && !isQuestCompleted();
    }

    private boolean isQuestCompleted() {
        return Microbot.getClientThread().runOnClientThread(() -> getQuestHelperPlugin().getSelectedQuest().isCompleted());
    }

    private boolean isInDialogOptions() {
        return Rs2Widget.isWidgetVisible(ComponentID.DIALOG_OPTION_OPTIONS);
    }

    private void handleDialogOptions(QuestStep questStep) {
        var dialogOptions = Rs2Widget.getWidget(ComponentID.DIALOG_OPTION_OPTIONS).getDynamicChildren();

        questStep.getChoices().getChoices().stream()
            .filter(choice -> choice.getExpectedPreviousLine() == null && choice.getExcludedStrings().stream().noneMatch(Rs2Widget::hasWidget))
            .forEach(choice -> dialogOptions.stream()
                .filter(dialogChoice -> dialogChoice.getText().endsWith(choice.getChoice()))
                .findFirst()
                .ifPresent(dialogChoice -> Rs2Keyboard.keyPress(dialogChoice.getOnKeyListener()[7].toString().charAt(0))));
    }

    private void highlightWidget(QuestStep questStep) {
        questStep.getWidgetsToHighlight().stream()
            .filter(WidgetHighlight.class::isInstance)
            .map(WidgetHighlight.class::cast)
            .filter(highlight -> Rs2Widget.isWidgetVisible(highlight.getGroupId(), highlight.getChildId()))
            .findFirst()
            .ifPresent(this::clickWidgetHighlight);
    }

    private void clickWidgetHighlight(WidgetHighlight highlight) {
        var widget = Rs2Widget.getWidget(highlight.getGroupId(), highlight.getChildId());
        if (widget != null) {
            var targetWidget = (highlight.getChildChildId() != -1) ? widget.getChildren()[highlight.getChildChildId()] : widget;
            if (targetWidget != null) Rs2Widget.clickWidget(targetWidget.getId());
        }
    }


    private QuestStep getCurrentQuestStep() {
        return Optional.ofNullable(getQuestHelperPlugin().getSelectedQuest().getCurrentStep())
            .map(QuestStep::getActiveStep)
            .orElse(null);
    }

    private boolean handleRequirements(DetailedQuestStep questStep) {
        return questStep.getRequirements().stream()
            .filter(ItemRequirement.class::isInstance)
            .map(ItemRequirement.class::cast)
            .filter(req -> req.isEquip() && Rs2Inventory.contains(req.getAllIds().toArray(new Integer[0])))
            .filter(req -> req.getAllIds().stream().noneMatch(Rs2Equipment::isWearing))
            .findFirst()
            .map(req -> Rs2Inventory.wear(req.getAllIds().stream().filter(Rs2Inventory::contains).findFirst().orElse(-1)))
            .isPresent();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        reset();
    }

    public static void reset() {
        itemsMissing.clear();
        itemRequirements.clear();
        grandExchangeItems.clear();
    }

    public boolean applyNpcStep(NpcStep step) {
        var npcs = step.getNpcs();
        var npc = npcs.stream().findFirst().orElse(null);

        if (step.isAllowMultipleHighlights()){
            if (npcs.stream().anyMatch(x -> !npcsHandled.contains(x)))
                npc = npcs.stream().filter(x -> !npcsHandled.contains(x)).findFirst().orElse(null);
            else
                npc = npcs.stream().min(Comparator.comparing(x -> Rs2Player.getWorldLocation().distanceTo(x.getWorldLocation()))).orElse(null);
        }

        // Workaround for instances
        if (npc != null && Rs2Camera.isTileOnScreen(npc.getLocalLocation()) && (Microbot.getClient().isInInstancedRegion() || Rs2Npc.canWalkTo(npc, 10))) {
            // Stop pathing
            Rs2Walker.setTarget(null);

            if (step.getText().stream().anyMatch(x -> x.toLowerCase().contains("kill"))) {
                if (!Rs2Combat.inCombat())
                    Rs2Npc.interact(step.npcID, "Attack");

                return true;
            }

            if (step instanceof NpcEmoteStep){
                var emoteStep = (NpcEmoteStep)step;

                for (Widget emoteWidget : Rs2Widget.getWidget(ComponentID.EMOTES_EMOTE_CONTAINER).getDynamicChildren())
                {
                    if (emoteWidget.getSpriteId() == emoteStep.getEmote().getSpriteId())
                    {
                        var id = emoteWidget.getOriginalX() / 42 + ((emoteWidget.getOriginalY() - 6) / 49) * 4;

                        Microbot.doInvoke(new NewMenuEntry("Perform", emoteWidget.getText(), 1, MenuAction.CC_OP, id, ComponentID.EMOTES_EMOTE_CONTAINER, false), new Rectangle(0, 0, 1, 1));
                        Rs2Player.waitForAnimation();

                        if (Rs2Dialogue.isInDialogue())
                            return false;
                    }
                }
            }

            var itemId = step.getIconItemID();
            if (itemId != -1){
                Rs2Inventory.use(itemId);
                Rs2Npc.interact(npc);
            } else
                Rs2Npc.interact(npc, chooseCorrectNPCOption(step, npc));

            if (step.isAllowMultipleHighlights()){
                npcsHandled.add(npc);
                // Might open up a dialog
                sleepUntil(Rs2Dialogue::isInDialogue);
            }
        } else if (npc != null && !Rs2Camera.isTileOnScreen(npc.getLocalLocation())) {
            Rs2Walker.walkTo(npc.getWorldLocation(), 2);
        } else if (npc != null && (!Rs2Npc.hasLineOfSight(npc) || !Rs2Npc.canWalkTo(npc, 10))) {
            Rs2Walker.walkTo(npc.getWorldLocation(), 2);
        } else {
            if (step.getWorldPoint().distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) > 3) {
                Rs2Walker.walkTo(step.getWorldPoint(), 2);
                return false;
            }
        }
        return true;
    }

    public boolean applyObjectStep(ObjectStep step) {
        var object = step.getObjects().stream().findFirst().orElse(null);
        var itemId = step.getIconItemID();

        if (step.getObjects().size() > 1){
            if (step.getObjects().stream().anyMatch(x -> !objectsHandeled.contains(x)))
                object = step.getObjects().stream().filter(x -> !objectsHandeled.contains(x)).findFirst().orElse(null);
            else
                object = step.getObjects().stream().min(Comparator.comparing(x -> Rs2Player.getWorldLocation().distanceTo(x.getWorldLocation()))).orElse(null);
        }

        if (object != null && unreachableTarget){
            var tileObjects = Rs2GameObject.getTileObjects().stream().filter(x -> x instanceof WallObject).collect(Collectors.toList());

            for (var tile : Rs2Tile.getWalkableTilesAroundTile(object.getWorldLocation(), unreachableTargetCheckDist)){
                if (tileObjects.stream().noneMatch(x -> x.getWorldLocation().equals(tile))){
                    if (!Rs2Walker.walkTo(tile) && ShortestPathPlugin.getPathfinder() == null)
                        return false;

                    sleepUntil(() -> ShortestPathPlugin.getPathfinder() == null || ShortestPathPlugin.getPathfinder().isDone());
                    if (ShortestPathPlugin.getPathfinder() == null || ShortestPathPlugin.getPathfinder().isDone()){
                        unreachableTarget = false;
                        unreachableTargetCheckDist = 1;
                    }
                    return false;
                }
            }

            unreachableTargetCheckDist++;
            return false;
        }

        /**
         * TODO: rework this block of code to handle walking closer to an object before interacting with it
         */
        if (step.getWorldPoint() != null && Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo2D(step.getWorldPoint()) > 1
                && !Rs2GameObject.canWalkTo(object, 10)) {
            WorldPoint targetTile = null;
            WorldPoint stepLocation = object == null ? step.getWorldPoint() : object.getWorldLocation();
            int radius = 0;
            while (targetTile == null) {
                if (mainScheduledFuture.isCancelled())
                    break;
                radius++;
                TileObject finalObject = object;
                targetTile = Rs2Tile.getWalkableTilesAroundTile(stepLocation, radius)
                        .stream().filter(x -> Rs2GameObject.hasLineOfSight(x, finalObject))
                        .sorted(Comparator.comparing(x -> x.distanceTo(Rs2Player.getWorldLocation()))).findFirst().orElse(null);

                if (radius > 10 && targetTile == null)
                    targetTile = stepLocation;
            }

            //target distance set to 3, because some npcs walk away from a player
            //so it can take a while to interact with the npc
            Rs2Walker.walkTo(targetTile, 3);

            if (ShortestPathPlugin.getPathfinder() != null){
                var path = ShortestPathPlugin.getPathfinder().getPath();
                if (path.get(path.size() - 1).distanceTo(step.getWorldPoint()) <= 1)
                    return false;
            } else
                return false;
        }

        if (Rs2GameObject.hasLineOfSight(object) || object != null && (Rs2Camera.isTileOnScreen(object) || object.getCanvasLocation() != null)){
            // Stop pathing
            Rs2Walker.setTarget(null);

            if (itemId == -1)
                Rs2GameObject.interact(object, chooseCorrectObjectOption(step, object));
            else{
                Rs2Inventory.use(itemId);
                Rs2GameObject.interact(object);
            }

            sleepUntil(() -> Rs2Player.isWalking() || Rs2Player.isAnimating());
            sleep(100);
            sleepUntil(() -> !Rs2Player.isWalking() && !Rs2Player.isAnimating());
            objectsHandeled.add(object);
        }

        return true;
    }

    private boolean applyDigStep(DigStep step){
        if (!Rs2Walker.walkTo(step.getWorldPoint()))
            return false;
        else if (!Rs2Player.getWorldLocation().equals(step.getWorldPoint()))
            Rs2Walker.walkFastCanvas(step.getWorldPoint());
        else {
            Rs2Inventory.interact(ItemID.SPADE, "Dig");
            return true;
        }

        return false;
    }

    private boolean applyPuzzleStep(PuzzleStep step){
        if (!step.getHighlightedButtons().isEmpty()){
            var widgetDetails = step.getHighlightedButtons().stream().filter(x -> Rs2Widget.isWidgetVisible(x.groupID, x.childID)).findFirst().orElse(null);
            if (widgetDetails != null){
                Rs2Widget.clickWidget(widgetDetails.groupID, widgetDetails.childID);
                return true;
            }
        }

        return false;
    }

    private String chooseCorrectObjectOption(QuestStep step, TileObject object){
        ObjectComposition objComp = Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getObjectDefinition(object.getId()));

        if (objComp == null)
            return "";

        String[] actions;
        if (objComp.getImpostorIds() != null) {
            actions = objComp.getImpostor().getActions();
        } else {
            actions = objComp.getActions();
        }

        for (var action : actions){
            if (action != null && step.getText().stream().anyMatch(x -> x.toLowerCase().contains(action.toLowerCase())))
                return action;
        }

        return "";
    }

    private String chooseCorrectNPCOption(QuestStep step, NPC npc){
        var npcComp = Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getNpcDefinition(npc.getId()));

        if (npcComp == null)
            return "Talk-to";

        for (var action : npcComp.getActions()){
            if (action != null && step.getText().stream().anyMatch(x -> x.toLowerCase().contains(action.toLowerCase())))
                return action;
        }

        return "Talk-to";
    }

    private String chooseCorrectItemOption(QuestStep step, int itemId){
        for (var action : Rs2Inventory.get(itemId).getInventoryActions()){
            if (action != null && step.getText().stream().anyMatch(x -> x.toLowerCase().contains(action.toLowerCase())))
                return action;
        }

        return "use";
    }

    private boolean applyDetailedQuestStep(DetailedQuestStep conditionalStep) {
        if (conditionalStep instanceof NpcStep) return false;

        if (conditionalStep.getIconItemID() != -1
                && conditionalStep.getWorldPoint() != null
                && !conditionalStep.getWorldPoint().toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Rs2Player.getWorldLocation())) {
            if (Rs2Tile.areSurroundingTilesWalkable(conditionalStep.getWorldPoint(), 1, 1)) {
                WorldPoint nearestUnreachableWalkableTile = Rs2Tile.getNearestWalkableTileWithLineOfSight(conditionalStep.getWorldPoint());
                if (nearestUnreachableWalkableTile != null) {
                    return Rs2Walker.walkTo(nearestUnreachableWalkableTile, 0);
                }
            }
        }

        boolean usingItems = false;
        for (Requirement requirement : conditionalStep.getRequirements()) {
            if (requirement instanceof ItemRequirement) {
                ItemRequirement itemRequirement = (ItemRequirement) requirement;

                if (itemRequirement.shouldHighlightInInventory(Microbot.getClient())
                    && Rs2Inventory.contains(itemRequirement.getAllIds().toArray(new Integer[0]))) {
                    var itemId = itemRequirement.getAllIds().stream().filter(Rs2Inventory::contains).findFirst().orElse(-1);
                    Rs2Inventory.interact(itemId, chooseCorrectItemOption(conditionalStep, itemId));
                    sleep(100, 200);
                    usingItems = true;
                    continue;
                }

                if (!Rs2Inventory.contains(itemRequirement.getAllIds().toArray(new Integer[0])) && conditionalStep.getWorldPoint() != null) {
                    if (Rs2Walker.canReach(conditionalStep.getWorldPoint()) &&
                            (conditionalStep.getWorldPoint().distanceTo(Rs2Player.getWorldLocation()) < 2)
                            || conditionalStep.getWorldPoint().toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Microbot.getClient().getLocalPlayer().getWorldLocation().toWorldArea())
                            && Rs2Camera.isTileOnScreen(LocalPoint.fromWorld(Microbot.getClient().getTopLevelWorldView(), conditionalStep.getWorldPoint()))) {
                        Rs2GroundItem.loot(itemRequirement.getId());
                    } else {
                        Rs2Walker.walkTo(conditionalStep.getWorldPoint(), 2);
                    }
                    return true;
                } else if (!Rs2Inventory.contains(itemRequirement.getAllIds().toArray(new Integer[0]))){
                    Rs2GroundItem.loot(itemRequirement.getId());
                    return true;
                }
            }
        }

        if (!usingItems && conditionalStep.getWorldPoint() != null && !Rs2Walker.walkTo(conditionalStep.getWorldPoint()))
            return true;

        if (conditionalStep.getIconItemID() != -1 && conditionalStep.getWorldPoint() != null
        && conditionalStep.getWorldPoint().toWorldArea().hasLineOfSightTo(Microbot.getClient().getTopLevelWorldView(), Rs2Player.getWorldLocation())) {
            if (conditionalStep.getQuestHelper().getQuest() == QuestHelperQuest.ZOGRE_FLESH_EATERS) {
                if (conditionalStep.getIconItemID() == 4836) { // strange potion
                    Rs2GroundItem.interact(ItemID.CUP_OF_TEA_4838, "", 20);
                }
            }
        }

        return usingItems;
    }

    private boolean applyWidgetStep(WidgetStep step) {
        var widgetDetails = step.getWidgetDetails().get(0);
        var widget = Microbot.getClient().getWidget(widgetDetails.groupID, widgetDetails.childID);

        if (widgetDetails.childChildID != -1){
            var tmpWidget = widget.getChild(widgetDetails.childChildID);

            if (tmpWidget != null)
                widget = tmpWidget;
        }

        return Rs2Widget.clickWidget(widget.getId());
    }

    protected QuestHelperPlugin getQuestHelperPlugin() {
        return (QuestHelperPlugin) Microbot.getPluginManager().getPlugins().stream()
            .filter(QuestHelperPlugin.class::isInstance)
            .findFirst()
            .orElse(null);
    }

    public void onChatMessage(ChatMessage chatMessage) {
        if (!chatMessage.getMessage().equalsIgnoreCase("I can't reach that!")) return;
        unreachableTarget = true;
    }
}

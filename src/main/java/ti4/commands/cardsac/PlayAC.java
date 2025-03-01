package ti4.commands.cardsac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ti4.commands.player.TurnStart;
import ti4.generator.Mapper;
import ti4.helpers.AgendaHelper;
import ti4.helpers.ButtonHelper;
import ti4.helpers.ButtonHelperActionCards;
import ti4.helpers.ButtonHelperFactionSpecific;
import ti4.helpers.ButtonHelperHeroes;
import ti4.helpers.CombatTempModHelper;
import ti4.helpers.Constants;
import ti4.helpers.Emojis;
import ti4.helpers.FoWHelper;
import ti4.helpers.Helper;
import ti4.map.Game;
import ti4.map.Player;
import ti4.message.MessageHelper;
import ti4.model.ActionCardModel;
import ti4.model.TemporaryCombatModifierModel;

public class PlayAC extends ACCardsSubcommandData {
    public PlayAC() {
        super(Constants.PLAY_AC, "Play an Action Card");
        addOptions(new OptionData(OptionType.STRING, Constants.ACTION_CARD_ID,
                "Action Card ID that is sent between () or Name/Part of Name").setRequired(true));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Game activeGame = getActiveGame();
        Player player = activeGame.getPlayer(getUser().getId());
        player = Helper.getGamePlayer(activeGame, player, event, null);
        if (player == null) {
            sendMessage("Player could not be found");
            return;
        }

        OptionMapping option = event.getOption(Constants.ACTION_CARD_ID);
        if (option == null) {
            sendMessage("Please select what Action Card to discard");
            return;
        }

        String reply = playAC(event, activeGame, player, option.getAsString().toLowerCase(), event.getChannel(),
                event.getGuild());
        if (reply != null) {
            sendMessage(reply);
        }
    }

    public static String playAC(GenericInteractionCreateEvent event, Game game, Player player, String value,
            MessageChannel channel, Guild guild) {
        MessageChannel mainGameChannel = game.getMainGameChannel() == null ? channel : game.getMainGameChannel();

        String acID = null;
        int acIndex = -1;
        try {
            acIndex = Integer.parseInt(value);
            for (Map.Entry<String, Integer> so : player.getActionCards().entrySet()) {
                if (so.getValue().equals(acIndex)) {
                    acID = so.getKey();
                }
            }
        } catch (Exception e) {
            boolean foundSimilarName = false;
            String cardName = "";
            for (Map.Entry<String, Integer> ac : player.getActionCards().entrySet()) {
                String actionCardName = Mapper.getActionCard(ac.getKey()).getName();
                if (actionCardName != null) {
                    actionCardName = actionCardName.toLowerCase();
                    if (actionCardName.contains(value)) {
                        if (foundSimilarName && !cardName.equals(actionCardName)) {
                            return "Multiple cards with similar name founds, please use ID";
                        }
                        acID = ac.getKey();
                        acIndex = ac.getValue();
                        foundSimilarName = true;
                        cardName = actionCardName;
                    }
                }
            }
        }
        if (acID == null) {
            // sendMessage();
            return "No such Action Card ID found, please retry";
        }
        ActionCardModel actionCard = Mapper.getActionCard(acID);
        String actionCardTitle = actionCard.getName();
        String actionCardWindow = actionCard.getWindow();

        String activePlayerID = game.getActivePlayerID();
        if (player.isPassed() && activePlayerID != null) {
            Player activePlayer = game.getPlayer(activePlayerID);
            if (activePlayer != null && activePlayer.hasTech("tp")) {
                return "You are passed and the active player has researched Transparasteel Plating. AC Play command cancelled.";
            }
        }
        if ("Action".equalsIgnoreCase(actionCardWindow) && game.getPlayer(activePlayerID) != player) {
            return "You are trying to play a component action AC and the game does not think you are the active player. You can fix this with /player turn_start. Until then, you are #denied";
        }
        if (ButtonHelper.isPlayerOverLimit(game, player)) {
            return player.getRepresentation(true, true)
                    + " The bot thinks you are over the limit and thus will not allow you to play ACs at this time. Ping Fin if this is an error";
        }

        if (player.hasAbility("cybernetic_madness")) {
            game.purgedActionCard(player.getUserID(), acIndex);
        } else {
            game.discardActionCard(player.getUserID(), acIndex);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(game.getPing()).append(" ").append(game.getName()).append("\n");
        if (game.isFoWMode()) {
            sb.append("Someone played an Action Card:\n");
        } else {
            sb.append(player.getRepresentation()).append(" played an Action Card:\n");
        }

        List<Button> buttons = new ArrayList<>();
        Button sabotageButton = Button.danger("sabotage_ac_" + actionCardTitle, "Cancel AC With Sabotage")
                .withEmoji(Emoji.fromFormatted(Emojis.Sabotage));
        buttons.add(sabotageButton);
        Player empy = Helper.getPlayerFromUnit(game, "empyrean_mech");
        if (empy != null && ButtonHelperFactionSpecific.isNextToEmpyMechs(game, player, empy)
                && !game.getLaws().containsKey("articles_war")) {
            Button empyButton = Button
                    .secondary("sabotage_empy_" + actionCardTitle, "Cancel " + actionCardTitle + " With Empyrean Mech ")
                    .withEmoji(Emoji.fromFormatted(Emojis.mech));
            List<Button> empyButtons = new ArrayList<>();
            empyButtons.add(empyButton);
            Button refuse = Button.danger("deleteButtons", "Delete These Buttons");
            empyButtons.add(refuse);
            MessageHelper.sendMessageToChannelWithButtons(empy.getCardsInfoThread(),
                    empy.getRepresentation(true, true)
                            + "You have mechs adjacent to the player who played the AC. Use Buttons to decide whether to cancel.",
                    empyButtons);
        }
        String instinctTrainingID = "it";
        for (Player player2 : game.getPlayers().values()) {
            if (!player.equals(player2) && player2.hasTechReady(instinctTrainingID) && player2.getStrategicCC() > 0) {
                Button instinctButton = Button
                        .secondary("sabotage_xxcha_" + actionCardTitle,
                                "Cancel " + actionCardTitle + " With Instinct Training")
                        .withEmoji(Emoji.fromFormatted(Emojis.Xxcha));
                List<Button> xxchaButtons = new ArrayList<>();
                xxchaButtons.add(instinctButton);
                Button refuse = Button.danger("deleteButtons", "Delete These Buttons");
                xxchaButtons.add(refuse);
                MessageHelper.sendMessageToChannelWithButtons(player2.getCardsInfoThread(), player2
                        .getRepresentation(true, true)
                        + "You have Instinct Training unexhausted and a cc available. Use Buttons to decide whether to cancel",
                        xxchaButtons);
            }

        }
        MessageEmbed acEmbed = actionCard.getRepresentationEmbed();
        Button noSabotageButton = Button.primary("no_sabotage", "No Sabotage")
                .withEmoji(Emoji.fromFormatted(Emojis.NoSabotage));
        buttons.add(noSabotageButton);
        if (acID.contains("sabo")) {
            MessageHelper.sendMessageToChannelWithEmbed(mainGameChannel, sb.toString(), acEmbed);
        } else {
            if (Helper.isSaboAllowed(game, player)) {
                MessageHelper.sendMessageToChannelWithEmbedsAndFactionReact(mainGameChannel, sb.toString(), game,
                        player, Collections.singletonList(acEmbed), buttons, true);
            } else {
                MessageHelper.sendMessageToChannelWithEmbed(mainGameChannel, sb.toString(), acEmbed);

                String noSabosMessage = "> Either all **Sabotage** are in the discard or the active player has "
                        + Emojis.Yssaril + "**Transparasteel Plating** and everyone else has passed.\n" +
                        "> " + Emojis.Xxcha + "**Instinct Training** and " + Emojis.Empyrean + Emojis.mech
                        + "**Watcher** may still be viable, who knows.";
                MessageHelper.sendMessageToChannel(mainGameChannel, noSabosMessage);
            }
            MessageChannel channel2 = ButtonHelper.getCorrectChannel(player, game);
            if (actionCardTitle.contains("Manipulate Investments")) {
                List<Button> scButtons = new ArrayList<>();
                for (int sc = 1; sc < 9; sc++) {
                    Emoji scEmoji = Emoji.fromFormatted(Emojis.getSCBackEmojiFromInteger(sc));
                    Button button;
                    if (scEmoji.getName().contains("SC") && scEmoji.getName().contains("Back")) {
                        button = Button.secondary("FFCC_" + player.getFaction() + "_increaseTGonSC_" + sc, " ")
                                .withEmoji(scEmoji);
                    } else {
                        button = Button.secondary("FFCC_" + player.getFaction() + "_increaseTGonSC_" + sc,
                                sc + " " + Helper.getSCName(sc, game));
                    }
                    scButtons.add(button);
                }
                scButtons.add(Button.danger("deleteButtons", "Done adding TG"));
                MessageHelper.sendMessageToChannelWithButtons(channel2,
                        player.getRepresentation() + " Use buttons to increase tgs on SCs. Each press adds 1tg.",
                        scButtons);
            }
            if (actionCardTitle.contains("Archaeological Expedition")) {
                List<Button> scButtons = ButtonHelperActionCards.getArcExpButtons(game, player);
                MessageHelper.sendMessageToChannelWithButtons(channel2,
                        player.getRepresentation()
                                + " After checking for sabos, use buttons to explore a planet type x 3 and gain any frags",
                        scButtons);
            }
            if (actionCardTitle.contains("Planetary Rigs")) {
                List<Button> acbuttons = ButtonHelperHeroes.getAttachmentSearchButtons(game, player);
                String msg = player.getRepresentation()
                        + " After checking for sabos, first declare what planet you mean to put an attachment on, then hit the button to resolve";
                MessageHelper.sendMessageToChannel(ButtonHelper.getCorrectChannel(player, game), msg, acbuttons);
            }

            String codedName = "Plagiarize";
            String codedMessage = player.getRepresentation()
                    + " After checking for sabos, use buttons to resolve. Reminder that all card targets (besides tech RESEARCH) should be declared now, before people decide on sabos. Resolve ";
            List<Button> codedButtons = new ArrayList<>();
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "getPlagiarizeButtons",
                        "Resolve Plagiarize"));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Mining Initiative";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "miningInitiative",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "War Machine";
            if (actionCardTitle.contains(codedName)) {
                player.addSpentThing("warmachine");
            }

            codedName = "Economic Initiative";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "economicInitiative",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Reveal Prototype";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "focusedResearch",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Skilled Retreat";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(
                        player.getFinsFactionCheckerPrefix() + "retreat_" + game.getActiveSystem() + "_skilled",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Reparations";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveReparationsStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Distinguished Councilor";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveDistinguished",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Uprising";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveUprisingStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Assassinate Representative";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveAssRepsStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Signal Jamming";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveSignalJammingStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Spy";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveSpyStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Political Stability";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolvePSStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Plague";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolvePlagueStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Experimental Battlestation";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveEBSStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Micrometeoroid Storm";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveMicrometeoroidStormStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Upgrade";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(
                        player.getFinsFactionCheckerPrefix() + "resolveUpgrade_" + game.getActiveSystem(),
                "Resolve " + codedName));
                if(game.getActiveSystem().isEmpty()){
                    MessageHelper.sendMessageToChannel(channel2, "The active system is currently non-existant, so this card cannot be automated");
                }else{
                    MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
                }
            }
            codedName = "Infiltrate";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveInfiltrateStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2,
                        codedMessage + codedName
                                + ". Warning, this will not work if the player has already removed their structures",
                        codedButtons);
            }
            codedName = "Emergency Repairs";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(
                        player.getFinsFactionCheckerPrefix() + "resolveEmergencyRepairs_" + game.getActiveSystem(),
                        "Resolve " + codedName));
                if(game.getActiveSystem().isEmpty()){
                    MessageHelper.sendMessageToChannel(channel2, "The active system is currently non-existant, so this card cannot be automated");
                }else{
                    MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
                }
            }
            codedName = "Insider Information";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveInsiderInformation",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Cripple Defenses";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveCrippleDefensesStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Impersonation";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveImpersonation",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Ancient Burial Sites";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveABSStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Salvage";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveSalvageStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Insubordination";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveInsubStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Frontline Deployment";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveFrontline",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Unexpected Action";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveUnexpected",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Harness Energy";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveHarness",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "War Effort";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveWarEffort",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Free Trade Initiative";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveFreeTrade",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Preparation";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolvePreparation",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Summit";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success("resolveSummit", "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Scuttle";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "startToScuttleAUnit_0",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Lucky Shot";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "startToLuckyShotAUnit_0",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Refit Troops";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveRefitTroops",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Seize Artifact";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveSeizeArtifactStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Diplomatic Pressure";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveDiplomaticPressureStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Decoy Operation";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(
                        player.getFinsFactionCheckerPrefix() + "resolveDecoyOperationStep1_" + game.getActiveSystem(),
                        "Resolve " + codedName));
                if(game.getActiveSystem().isEmpty()){
                    MessageHelper.sendMessageToChannel(channel2, "The active system is currently non-existant, so this card cannot be automated");
                }else{
                    MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
                }
            }
            codedName = "Reactor Meltdown";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveReactorMeltdownStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Unstable Planet";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveUnstableStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Ghost Ship";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveGhostShipStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Tactical Bombardment";
            if (actionCardTitle.contains(codedName)) {
                codedButtons
                        .add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveTacticalBombardmentStep1",
                                "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Exploration Probe";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveProbeStep1",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Rally";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(
                        Button.success(player.getFinsFactionCheckerPrefix() + "resolveRally", "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Industrial Initiative";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "industrialInitiative",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Repeal Law";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "getRepealLawButtons",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            // "resolveCounterStroke"
            codedName = "Counterstroke";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveCounterStroke_"+game.getActiveSystem(),
                        "Resolve " + codedName));
                if(game.getActiveSystem().isEmpty()){
                    MessageHelper.sendMessageToChannel(channel2, "The active system is currently non-existant, so this card cannot be automated");
                }else{
                    MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
                }
            }
            codedName = "Divert Funding";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "getDivertFundingButtons",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            codedName = "Emergency Meeting";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "resolveEmergencyMeeting",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Focused Research";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "focusedResearch",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Forward Supply Base";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "forwardSupplyBase",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Rise of a Messiah";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "riseOfAMessiah",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }

            codedName = "Veto";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(
                        Button.primary(player.getFinsFactionCheckerPrefix() + "flip_agenda", "Reveal next Agenda"));
                MessageHelper.sendMessageToChannelWithButtons(game.getMainGameChannel(), codedMessage + codedName,
                        codedButtons);
            }

            codedName = "Fighter Conscription";
            if (actionCardTitle.contains(codedName)) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "fighterConscription",
                        "Resolve " + codedName));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + codedName, codedButtons);
            }
            TemporaryCombatModifierModel combatModAC = CombatTempModHelper.GetPossibleTempModifier(Constants.AC,
                    actionCard.getAlias(), player.getNumberTurns());
            if (combatModAC != null) {
                codedButtons.add(Button.success(player.getFinsFactionCheckerPrefix() + "applytempcombatmod__"
                        + Constants.AC + "__" + actionCard.getAlias(), "Resolve " + actionCard.getName()));
                MessageHelper.sendMessageToChannelWithButtons(channel2, codedMessage + actionCard.getName(),
                        codedButtons);
            }

            if (actionCardWindow.contains("After an agenda is revealed")) {

                List<Button> afterButtons = AgendaHelper.getAfterButtons(game);
                MessageHelper.sendMessageToChannelWithPersistentReacts(mainGameChannel,
                        "Please indicate no afters again.", game, afterButtons, "after");
                Date newTime = new Date();
                game.setLastActivePlayerPing(newTime);

                String finChecker = "FFCC_" + player.getFaction() + "_";
                if (actionCardTitle.contains("Rider") || actionCardTitle.contains("Sanction")) {
                    List<Button> riderButtons = AgendaHelper.getAgendaButtons(actionCardTitle, game, finChecker);
                    MessageHelper.sendMessageToChannelWithFactionReact(mainGameChannel,
                            "Please select your rider target", game, player, riderButtons);
                }
                if (actionCardTitle.contains("Hack Election")) {
                    game.setHackElectionStatus(true);
                    Button setHack = Button.danger("hack_election", "Set the voting order as normal");
                    List<Button> hackButtons = List.of(setHack);
                    MessageHelper.sendMessageToChannelWithFactionReact(mainGameChannel,
                            "Voting order reversed. Please hit this button if hack election is sabod", game, player,
                            hackButtons);
                }

            }
            if (actionCardWindow.contains("When an agenda is revealed") && !actionCardTitle.contains("Veto")) {
                Date newTime = new Date();
                game.setLastActivePlayerPing(newTime);
                List<Button> whenButtons = AgendaHelper.getWhenButtons(game);
                MessageHelper.sendMessageToChannelWithPersistentReacts(mainGameChannel,
                        "Please indicate no whens again.", game, whenButtons, "when");
            }
            if ("Action".equalsIgnoreCase(actionCardWindow)) {
                String message = "Use buttons to end turn or do another action.";
                game.setJustPlayedComponentAC(true);
                List<Button> systemButtons = TurnStart.getStartOfTurnButtons(player, game, true, event);
                MessageHelper.sendMessageToChannelWithButtons(channel2, message, systemButtons);
                if (player.getLeaderIDs().contains("kelerescommander")
                        && !player.hasLeaderUnlocked("kelerescommander")) {
                    String message2 = player.getRepresentation(true, true)
                            + " you can unlock keleres commander (if the AC isnt sabod) by paying 1tg.";
                    List<Button> buttons2 = new ArrayList<>();
                    buttons2.add(Button.success("pay1tgforKeleres", "Pay 1tg to unlock Commander"));
                    buttons2.add(Button.danger("deleteButtons", "Decline"));
                    MessageHelper.sendMessageToChannelWithButtons(channel2, message2, buttons2);
                }
                for (Player p2 : game.getRealPlayers()) {
                    if (p2 == player) {
                        continue;
                    }
                    if (p2.getActionCards().containsKey("reverse_engineer")
                            && !ButtonHelper.isPlayerElected(game, player, "censure")
                            && !ButtonHelper.isPlayerElected(game, player, "absol_censure")) {
                        List<Button> reverseButtons = new ArrayList<>();
                        String key = "reverse_engineer";
                        String ac_name = Mapper.getActionCard(key).getName();
                        if (ac_name != null) {
                            reverseButtons.add(Button.success(Constants.AC_PLAY_FROM_HAND + p2.getActionCards().get(key)
                                    + "_reverse_" + actionCardTitle, "Reverse engineer " + actionCardTitle));
                        }
                        reverseButtons.add(Button.danger("deleteButtons", "Decline"));
                        String cyberMessage = "" + p2.getRepresentation(true, true)
                                + " reminder that you can use reverse engineer on " + actionCardTitle;
                        MessageHelper.sendMessageToChannelWithButtons(p2.getCardsInfoThread(),
                                cyberMessage, reverseButtons);
                    }
                }
            }
        }

        // Fog of war ping
        if (game.isFoWMode()) {
            String fowMessage = player.getRepresentation() + " played an Action Card: " + actionCardTitle;
            FoWHelper.pingAllPlayersWithFullStats(game, event, player, fowMessage);
            MessageHelper.sendPrivateMessageToPlayer(player, game, "Played action card: " + actionCardTitle);
        }
        if (player.hasUnexhaustedLeader("cymiaeagent") && player.getStrategicCC() > 0) {
            List<Button> buttons2 = new ArrayList<>();
            Button hacanButton = Button.secondary("exhaustAgent_cymiaeagent_" + player.getFaction(), "Use Cymiae Agent")
                    .withEmoji(Emoji.fromFormatted(Emojis.cymiae));
            buttons2.add(hacanButton);
            MessageHelper.sendMessageToChannelWithButtons(ButtonHelper.getCorrectChannel(player, game),
                    player.getRepresentation(true, true) + " you can use Cymiae agent to draw an AC",
                    buttons2);
        }

        ACInfo.sendActionCardInfo(game, player);
        return null;
    }
}
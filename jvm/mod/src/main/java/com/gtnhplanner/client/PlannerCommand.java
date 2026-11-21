package com.gtnhplanner.client;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

/**
 * {@code /gtnhplanner <verb> <id>}: what the site's assistant hands the player, typed, clicked in chat (a RUN_COMMAND
 * button, caught by Forge's client command handler) or run by the assistant; all three behave the same, with no
 * question asked, so every verb only adds and can be undone. Registered on the client only (the client proxy): a
 * dedicated server never loads it.
 */
public class PlannerCommand extends CommandBase {

    /** The verbs, one entry each: the word, the kind letter of its id, what it does. */
    static final List<PlannerVerbs.Verb> VERBS = List.of(
        new PlannerVerbs.Verb("open", 'd', Deliveries::open),
        new PlannerVerbs.Verb("apply", 'p', Deliveries::apply));

    @Override
    public String getCommandName() {
        return PlannerVerbs.NAME;
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return PlannerVerbs.usage(VERBS);
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        final PlannerVerbs.Call call = PlannerVerbs.read(VERBS, args);
        if (call == null) {
            Deliveries.say(PlannerVerbs.usage(VERBS), true);
            return;
        }
        call.verb()
            .run()
            .accept(call.id());
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}

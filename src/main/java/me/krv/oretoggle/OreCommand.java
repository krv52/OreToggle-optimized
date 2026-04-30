package me.krv.oretoggle;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class OreCommand implements CommandExecutor, TabCompleter {
    private final OreDefinitions definitions;
    private final OreService service;

    OreCommand(OreDefinitions definitions, OreService service) {
        this.definitions = definitions;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        return switch (commandName) {
            case "toggleore", "oretoggle" -> handleToggleOre(sender, args);
            case "restoreore" -> handleRestoreOre(sender, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return definitions.keys().stream()
                    .filter(key -> key.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }

        if ((command.getName().equalsIgnoreCase("toggleore") || command.getName().equalsIgnoreCase("oretoggle")) && args.length == 2) {
            return List.of("on", "off").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return Collections.emptyList();
    }

    private boolean handleToggleOre(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /toggleore <ore> <on|off>");
            return true;
        }

        if (service.hasActiveJob()) {
            sender.sendMessage("Another ore operation is already running: " + service.activeJobDescription());
            return true;
        }

        OreDefinition definition = definitions.get(normalizeKey(args[0]));
        if (definition == null) {
            sender.sendMessage("Unknown ore. Available: " + String.join(", ", definitions.keys()));
            return true;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!mode.equals("on") && !mode.equals("off")) {
            sender.sendMessage("Second argument must be on or off.");
            return true;
        }

        if (mode.equals("on")) {
            service.enableOre(definition);
            sender.sendMessage(definition.displayName() + " is now enabled. New chunks will not be processed.");
            return true;
        }

        int queued = service.disableOre(definition);
        sender.sendMessage(
                definition.displayName() + " is now disabled. Queued " + queued + " nearby chunks around players. "
                        + "New chunks will be processed lazily."
        );
        return true;
    }

    private boolean handleRestoreOre(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /restoreore <ore>");
            return true;
        }

        if (service.hasActiveJob()) {
            sender.sendMessage("Another ore operation is already running: " + service.activeJobDescription());
            return true;
        }

        OreDefinition definition = definitions.get(normalizeKey(args[0]));
        if (definition == null) {
            sender.sendMessage("Unknown ore. Available: " + String.join(", ", definitions.keys()));
            return true;
        }

        service.restoreOre(sender, definition);
        return true;
    }

    private String normalizeKey(String input) {
        return input.toLowerCase(Locale.ROOT);
    }
}

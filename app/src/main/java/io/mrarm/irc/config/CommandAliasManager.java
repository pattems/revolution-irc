package io.mrarm.irc.config;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.util.CommandAliasSyntaxParser;
import io.mrarm.irc.util.SimpleTextVariableList;

public class CommandAliasManager {

    static Pattern mMatchVariablesRegex = Pattern.compile("(?<!\\\\)\\$\\{(.*?)\\}");

    public static final String ALIASES_PATH = "command_aliases.json";

    public static final String VAR_CTCP_DELIM = "ctcp_delim";
    public static final String VAR_CTCP_DELIM_VALUE = "\001";

    public static final String VAR_CHANNEL = "channel";
    public static final String VAR_MYNICK = "nick";
    public static final String VAR_ARGS = "args";

    private static final SimpleTextVariableList sDefaultVariables;
    private static final List<CommandAlias> sDefaultAliases;

    private static CommandAliasManager sInstance;

    private Context mContext;
    private List<CommandAlias> mUserAliases;

    static {
        sDefaultVariables = new SimpleTextVariableList();
        sDefaultVariables.set(VAR_CTCP_DELIM, VAR_CTCP_DELIM_VALUE);

        sDefaultAliases = new ArrayList<>();
        sDefaultAliases.add(CommandAlias.raw("raw", "<command>", "${command}"));
        sDefaultAliases.add(CommandAlias.raw("join", "<channels> [keys]", "JOIN ${channels} ${keys}"));
        sDefaultAliases.add(CommandAlias.raw("part", "[channel] [message...]", "PART ${channel} :${message}"));
        sDefaultAliases.add(CommandAlias.raw("nick", "<new-nick>", "NICK ${new-nick}"));
        sDefaultAliases.add(CommandAlias.message("msg", "<channel> <message...>", "${channel}", "${message}"));
        sDefaultAliases.add(CommandAlias.message("me", "<message...>", "${channel}", "${ctcp_delim}ACTION ${message}${ctcp_delim}"));
        sDefaultAliases.add(CommandAlias.raw("kick", "[channel] <user> [message...]", "KICK ${channel} ${user} :${message}"));
        sDefaultAliases.add(CommandAlias.raw("mode", "[channel] <modes> [args...]", "MODE ${channel} ${modes} ${args}"));
        sDefaultAliases.add(CommandAlias.raw("quit", "[message...]", "QUIT :${message}"));
    }

    public static CommandAliasManager getInstance(Context context) {
        if (sInstance == null)
            sInstance = new CommandAliasManager(context.getApplicationContext());
        return sInstance;
    }

    public static List<CommandAlias> getDefaultAliases() {
        return sDefaultAliases;
    }

    public CommandAliasManager(Context context) {
        mContext = context;
        mUserAliases = new ArrayList<>();
        loadUserSettings();
    }

    public void loadUserSettings(Reader reader) {
        UserAliasesSettings settings = SettingsHelper.getGson().fromJson(reader,
                UserAliasesSettings.class);
        mUserAliases = settings.userAliases;
    }

    public void loadUserSettings() {
        try {
            loadUserSettings(new BufferedReader(new FileReader(
                    new File(mContext.getFilesDir(), ALIASES_PATH))));
        } catch (Exception ignored) {
        }
    }

    public void saveUserSettings(Writer writer) {
        UserAliasesSettings settings = new UserAliasesSettings();
        settings.userAliases = mUserAliases;
        SettingsHelper.getGson().toJson(settings, writer);
    }

    public boolean saveUserSettings() {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(
                    new File(mContext.getFilesDir(), ALIASES_PATH)));
            saveUserSettings(writer);
            writer.close();
            return true;
        } catch (IOException ignored) {
        }
        return false;
    }

    private static class UserAliasesSettings {

        List<CommandAlias> userAliases;

    }

    public List<CommandAlias> getUserAliases() {
        return mUserAliases;
    }

    public CommandAlias findCommandAlias(String name, String syntax) {
        for (CommandAlias a : mUserAliases) {
            if (a.name.equalsIgnoreCase(name) && a.syntax.equals(syntax))
                return a;
        }
        for (CommandAlias a : sDefaultAliases) {
            if (a.name.equalsIgnoreCase(name) && a.syntax.equals(syntax))
                return a;
        }
        return null;
    }

    public CommandAlias findCommandAlias(IRCConnection connection, String[] args) {
        for (CommandAlias a : mUserAliases) {
            if (a.name.equalsIgnoreCase(args[0]) && a.checkSyntaxMatches(connection.getServerConnectionData(), args))
                return a;
        }
        for (CommandAlias a : sDefaultAliases) {
            if (a.name.equalsIgnoreCase(args[0]) && a.checkSyntaxMatches(connection.getServerConnectionData(), args))
                return a;
        }
        return null;
    }

    private String processVariables(String str, SimpleTextVariableList variables) {
        Matcher matcher = mMatchVariablesRegex.matcher(str);
        StringBuffer buf = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(1);
            String replaceWith = variables.get(text);
            if (replaceWith == null)
                replaceWith = sDefaultVariables.get(text);
            if (replaceWith == null)
                replaceWith = "";
            matcher.appendReplacement(buf, Matcher.quoteReplacement(replaceWith));
        }
        matcher.appendTail(buf);
        return buf.toString();
    }

    public void processCommand(IRCConnection connection, String command, SimpleTextVariableList vars) {
        String[] args = command.split(" ");
        CommandAlias alias = findCommandAlias(connection, args);
        if (alias == null)
            return;
        String[] argsVar = new String[args.length - 1];
        System.arraycopy(args, 1, argsVar, 0, argsVar.length);
        vars.set(VAR_ARGS, Arrays.asList(argsVar), " ");
        alias.getSyntaxParser().process(connection.getServerConnectionData(), args, 1, vars);
        String processedText = processVariables(alias.text, vars);
        if (alias.mode == CommandAlias.MODE_RAW) {
            connection.sendCommandRaw(processedText, null, null);
        } else if (alias.mode == CommandAlias.MODE_MESSAGE) {
            String processedChannel = processVariables(alias.channel, vars);
            connection.sendMessage(processedChannel, processedText, null, null);
        }
    }


    public static class CommandAlias {

        public static final int MODE_MESSAGE = 0;
        public static final int MODE_RAW = 1;

        public String name;
        public String syntax;
        public String text;
        public int mode;
        public String channel;
        private transient CommandAliasSyntaxParser syntaxParser;

        public CommandAlias() {
        }

        public CommandAliasSyntaxParser getSyntaxParser() {
            if (syntaxParser != null && syntaxParser.getSyntax().equals(syntax))
                return syntaxParser;
            try {
                syntaxParser = new CommandAliasSyntaxParser(syntax);
            } catch (ParseException ignored) {
            }
            return syntaxParser;
        }

        public boolean checkSyntaxMatches(ServerConnectionData info, String[] args) {
            CommandAliasSyntaxParser parser = getSyntaxParser();
            if (parser == null)
                return false;
            return parser.matches(info, args, 1);
        }

        public static CommandAlias raw(String name, String syntax, String text) {
            CommandAlias ret = new CommandAlias();
            ret.name = name;
            ret.syntax = syntax;
            ret.text = text;
            ret.mode = MODE_RAW;
            return ret;
        }

        public static CommandAlias message(String name, String syntax, String channel, String message) {
            CommandAlias ret = new CommandAlias();
            ret.name = name;
            ret.syntax = syntax;
            ret.text = message;
            ret.channel = channel;
            ret.mode = MODE_MESSAGE;
            return ret;
        }

    }

}

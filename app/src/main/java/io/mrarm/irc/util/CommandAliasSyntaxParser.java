package io.mrarm.irc.util;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.mrarm.chatlib.irc.ServerConnectionData;

public class CommandAliasSyntaxParser {

    private static final int ELEMENT_TEXT = 0;
    private static final int ELEMENT_CHANNEL = 1;
    private static final int ELEMENT_CHANNELS = 2;
    private static final int ELEMENT_USER = 3;
    private static final int ELEMENT_USERS = 4;

    private String syntax;
    private List<SyntaxElement> elements = new ArrayList<>();
    private int requiredArgCount = 0;

    public CommandAliasSyntaxParser(String syntax) throws ParseException {
        this.syntax = syntax;
        String[] parts = syntax.split(" ");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            SyntaxElement element = new SyntaxElement();
            if (part.startsWith("<") && part.endsWith(">")) {
                // required
                element.required = true;
                element.varName = part.substring(1, part.length() - 1);
                requiredArgCount++;
            } else if (part.startsWith("[") && part.endsWith("]")) {
                // optional
                element.varName = part.substring(1, part.length() - 1);
            } else {
                throw new ParseException("Invalid command alias syntax", -1);
            }
            if (element.varName.endsWith("...")) {
                if (i != parts.length -1)
                    throw new ParseException("Variable-length argument must be the last one", -1);
                element.vaarg = true;
                element.varName = element.varName.substring(0, element.varName.length() - 3);
            }
            if (element.varName.equals("channel") || element.varName.endsWith("-channel"))
                element.type = ELEMENT_CHANNEL;
            else if (element.varName.equals("channels") || element.varName.endsWith("-channels"))
                element.type = ELEMENT_CHANNELS;
            else if (element.varName.equals("user") || element.varName.endsWith("-user"))
                element.type = ELEMENT_USER;
            else if (element.varName.equals("users") || element.varName.endsWith("-users"))
                element.type = ELEMENT_USERS;
            elements.add(element);
        }
    }

    public String getSyntax() {
        return syntax;
    }

    public boolean matches(ServerConnectionData connection, String[] args, int argi) {
        if (args.length - argi < requiredArgCount)
            return false;
        int optargi = args.length - argi - requiredArgCount;
        for (SyntaxElement el : elements) {
            if (el.vaarg)
                return true;
            if (!el.required && optargi == 0)
                continue;
            boolean matches = el.checkArgumentMatches(connection, args[argi]);
            if (el.required) {
                if (!matches)
                    return false;
                argi++;
                continue;
            }
            if (!matches)
                continue;
            optargi--;
            argi++;
        }
        return argi == args.length;
    }

    public void process(ServerConnectionData connection, String[] args, int argi, SimpleTextVariableList vars) {
        int optargi = args.length - argi - requiredArgCount;
        for (SyntaxElement el : elements) {
            if (el.vaarg) {
                String[] vaargs = new String[args.length - argi];
                System.arraycopy(args, argi, vaargs, 0, vaargs.length);
                vars.set(el.varName, Arrays.asList(vaargs), " ");
                break;
            }
            if (!el.required && optargi == 0)
                continue;
            boolean matches = el.checkArgumentMatches(connection, args[argi]);
            if (!matches)
                continue;
            vars.set(el.varName, args[argi]);
            if (!el.required)
                optargi--;
            argi++;
        }
    }

    private static class SyntaxElement {

        int type = ELEMENT_TEXT;
        String varName;
        boolean required = false;
        boolean vaarg = false;

        boolean checkArgumentMatches(ServerConnectionData connection, String arg) {
            if (type == ELEMENT_CHANNEL || type == ELEMENT_CHANNELS) {
                return arg.length() >= 1 && connection.getSupportList().getSupportedChannelTypes()
                        .contains(arg.charAt(0));
            }
            return true;
        }

    }

}

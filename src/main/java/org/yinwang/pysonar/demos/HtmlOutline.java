package org.yinwang.pysonar.demos;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.Analyzer;
import org.yinwang.pysonar.Outliner;
import org.yinwang.pysonar.$;

import java.util.List;


class HtmlOutline {

    private Analyzer analyzer;
    private Linker linker;
    @Nullable
    private StringBuilder buffer;


    public HtmlOutline(Analyzer idx, Linker linker) {
        this.analyzer = idx;
        this.linker = linker;
    }


    @NotNull
    public String generate(String path) {
        buffer = new StringBuilder(1024);
        List<Outliner.Entry> entries = generateOutline(analyzer, path);
        addOutline(entries);
        String html = buffer.toString();
        buffer = null;
        return html;
    }


    @NotNull
    public List<Outliner.Entry> generateOutline(Analyzer analyzer, @NotNull String file) {
        return new Outliner().generate(analyzer, file);
    }


    private void addOutline(@NotNull List<Outliner.Entry> entries) {
        add("<ul class='outline-list'>\n");
        for (Outliner.Entry e : entries) {
            addEntry(e);
        }
        add("</ul>\n");
    }


    private void addEntry(@NotNull Outliner.Entry e) {
        add("<li>");

        String style = null;
        switch (e.kind) {
            case FUNCTION:
            case METHOD:
            case CONSTRUCTOR:
                style = "function";
                break;
            case CLASS:
                style = "type-name";
                break;
            case PARAMETER:
                style = "parameter";
                break;
            case VARIABLE:
            case SCOPE:
                style = "identifier";
                break;
        }

        String qname = linker.localQname(e.getQname());
        add("<a href='#");
        add(escapeAttribute(qname));
        add("' xid='");
        add(escapeAttribute(qname));
        add("'>");
        add(escapeText(e.getName()));
        add("</a>");

        if (e.isBranch()) {
            addOutline(e.getChildren());
        }
        add("</li>");
    }


    private void add(String text) {
        buffer.append(text);
    }

    private String escapeAttribute(String text) {
        return escapeText(text).replace("'", "&#39;").replace("\"", "&quot;");
    }

    private String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

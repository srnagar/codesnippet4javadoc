/**
 * Codesnippet Javadoc Doclet
 * Copyright (C) 2015-2017 Jaroslav Tulach <jaroslav.tulach@apidesign.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.0 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://opensource.org/licenses/GPL-3.0.
 */
package org.apidesign.javadoc.codesnippet;

import com.sun.javadoc.Doc;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.Tag;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Snippets {
    private static final Pattern TAG = Pattern.compile("\\{ *@codesnippet *([\\.\\-a-z0-9A-Z#]*) *\\}");
    private static final Pattern LINKTAG = Pattern.compile("\\{ *@link *([\\.\\-a-z0-9A-Z#]*) *\\}");
    private static final Pattern PACKAGE = Pattern.compile(" *package *([\\p{Alnum}\\.]+);");
    private static final Pattern IMPORT = Pattern.compile(" *import *([\\p{Alnum}\\.\\*]+);");
    private static final Pattern BEGIN = Pattern.compile(".* BEGIN: *(\\p{Graph}+)[-\\> ]*");
    private static final Pattern END = Pattern.compile(".* (END|FINISH): *(\\p{Graph}+)[-\\> ]*");
    private final DocErrorReporter reporter;
    private final List<Path> search = new ArrayList<>();
    private final List<Path> visible = new ArrayList<>();
    private final List<Pattern> classes = new ArrayList<>();
    private Map<String,String> snippets;
    private int maxLineLength = 80;
    private String verifySince;
    private Set<String> hiddenAnno;

    Snippets(DocErrorReporter reporter) {
        this.reporter = reporter;
    }

    void fixCodesnippets(Doc enclosingElement, Doc element) {
        try {
            for (;;) {
                final String txt = element.getRawCommentText();
                Matcher match = TAG.matcher(txt);
                if (!match.find()) {
                    if (classes.isEmpty()) {
                        break;
                    }
                    match = LINKTAG.matcher(txt);
                    if (!findLinkSnippet(match)) {
                        break;
                    }
                }
                final String code = "<pre>" + findSnippet(element, match.group(1)) + "</pre>";
                String newTxt = txt.substring(0, match.start(0)) +
                    code +
                    txt.substring(match.end(0));
                element.setRawCommentText(newTxt);
            }
            element.inlineTags();
            if (verifySince != null) {
                verifySinceTag(element, enclosingElement, verifySince);
            }
        } catch (IOException ex) {
            Logger.getLogger(Snippets.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private boolean verifySinceTag(Doc element, Doc enclosingElement, String expVersion) throws IOException {
        for (Tag t : element.tags()) {
            if (t.name().equals("@since")) {
                return false;
            }
        }
        if (enclosingElement.isEnum() && element.isMethod()) {
            if (element.name().equals("valueOf") || element.name().equals("values")) {
                // skip these well known autogenerated methods
                return false;
            }
        }
        reporter.printWarning(element.position(), "missing @since tag for " + element);
        if (!expVersion.isEmpty()) {
            addSinceTag(element, expVersion);
            return true;
        } else {
            return false;
        }
    }

    private void addSinceTag(Doc element, final String version) throws IOException {
        final File f = element.position().file();
        int index = element.position().line();
        List<String> lines = Files.readAllLines(f.toPath(), Charset.forName("UTF-8"));
        boolean second = false;
        for (;;) {
            String l = lines.get(--index);
            int at = l.indexOf("*/");
            if (at >= 0) {
                if (l.contains("@since " + version)) {
                    break;
                }
                lines.set(index, l.substring(0, at) + "@since " + version + " */");
                break;
            }
            if (l.isEmpty()) {
                lines.set(index, l + "/** @since " + version + " */");
                break;
            }
            if (l.endsWith(";")) {
                if (second) {
                    lines.set(index, l + " /** @since " + version + " */");
                    break;
                }
                second = true;
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            for (String l : lines) {
                w.write(l);
                w.newLine();
            }
        }
    }

    private boolean findLinkSnippet(Matcher match) {
        for (;;) {
            if (!match.find()) {
                return false;
            }
            String className = match.group(1);
            for(Pattern p : classes) {
                if (p.matcher(className).matches()) {
                    return true;
                }
            }
        }
    }

    String findSnippet(Doc element, String key) {
        if (snippets == null) {
            Map<String,String> tmp = new TreeMap<>();
            final Map<String,String> topClasses = new TreeMap<>();
            for (Path path : visible) {
                if (!Files.isDirectory(path)) {
                    printWarning(null, "Cannot scan " + path + " not a directory!");
                    continue;
                }
                try {
                    collectClasses(path, topClasses);
                } catch (IOException ex) {
                    printError(element, "Cannot read " + path + ": " + ex.getMessage());
                }
            }
            for (Path path : search) {
                if (!Files.isDirectory(path)) {
                    printWarning(null, "Cannot scan " + path + " not a directory!");
                    continue;
                }
                try {
                    scanDir(path, topClasses, tmp);
                } catch (IOException ex) {
                    printError(element, "Cannot read " + path + ": " + ex.getMessage());
                }
            }
            snippets = tmp;
        }
        String code = snippets.get(key);
        if (code == null) {
            reporter.printWarning(element.position(), code = "Snippet '" + key + "' not found.");
        }
        return code;
    }

    void addPath(Path path, boolean useLink) {
        search.add(path);
        if (useLink) {
            visible.add(path);
        }
    }

    void addClasses(String classRegExp) {
        classes.add(Pattern.compile(classRegExp));
    }

    private void scanDir(Path dir, final Map<String,String> topClasses, final Map<String, String> collect) throws IOException {
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String javaName = javaName(file);
                Map<String,CharSequence> texts = new TreeMap<>();
                Map<String,String> imports = new TreeMap<>(topClasses);
                Set<String> packages = new LinkedHashSet<>();
                try {
                    BufferedReader r = Files.newBufferedReader(file, Charset.defaultCharset());
                    for (;;) {
                        String line = r.readLine();
                        if (line == null) {
                            break;
                        }
                        if (javaName != null) {
                            Matcher m = IMPORT.matcher(line);
                            if (m.matches()) {
                                final String fqn = m.group(1);
                                if (fqn.endsWith(".*")) {
                                    packages.add(fqn.substring(0, fqn.length() - 2));
                                } else {
                                    int lastDot = fqn.lastIndexOf('.');
                                    imports.put(fqn.substring(lastDot + 1), fqn);
                                }
                            }
                        }
                        {
                            Matcher m = BEGIN.matcher(line);
                            if (m.matches()) {
                                Item sb = new Item(file);
                                CharSequence prev = texts.put(m.group(1), sb);
                                if (prev != null) {
                                    printError(null, "Same pattern is there twice: " + m.group(1) + " in " + file);
                                }
                                continue;
                            }
                        }
                        {
                            Matcher m = END.matcher(line);
                            if (m.matches()) {
                                CharSequence s = texts.get(m.group(2));
                                if (s instanceof Item) {
                                    texts.put(m.group(2), ((Item) s).toString(m.group(1).equals("FINISH"), imports, packages));
                                    continue;
                                }

                                if (s == null) {
                                    printError(null, "Closing unknown section: " + m.group(2) + " in " + file);
                                    continue;
                                }
                                printError(null, "Closing not opened section: " + m.group(2) + " in " + file);
                                continue;
                            }
                        }

                        for (CharSequence charSequence : texts.values()) {
                            if (charSequence instanceof Item) {
                                Item sb = (Item) charSequence;
                                sb.append(line);
                            }
                        }
                    }
                } catch (MalformedInputException ex) {
                    printWarning(null, "Skipping binary file " + file.toString());
                } catch (IOException ex) {
                    printError(null, "Cannot read " + file.toString() + " " + ex.getMessage());
                }
                for (Map.Entry<String, CharSequence> entry : texts.entrySet()) {
                    CharSequence v = entry.getValue();
                    if (v instanceof Item) {
                        printError(null, "Not closed section " + entry.getKey() + " in " + file);
                    }
                    collect.put(entry.getKey(), v.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });


//            for (Map.Entry<String, CharSequence> entry : texts.entrySet()) {
//                String text = entry.getValue().toString();
//                String out = linize(text);
//            }

    }

    private void collectClasses(Path dir, final Map<String, String> topClasses) throws IOException {
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String javaName = javaName(file);
                if (javaName != null) {
                    try {
                        BufferedReader r = Files.newBufferedReader(file, Charset.defaultCharset());
                        for (;;) {
                            String line = r.readLine();
                            if (line == null) {
                                break;
                            }
                            Matcher pkgMatch = PACKAGE.matcher(line);
                            if (pkgMatch.matches()) {
                                final String fqn = pkgMatch.group(1);
                                topClasses.put(javaName, fqn + '.' + javaName);
                            }
                        }
                    } catch (IOException ex) {
                        printError(null, "Cannot read " + file.toString() + " " + ex.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void printWarning(Doc where, String msg) {
        if (reporter != null) {
            if (where == null) {
                reporter.printWarning(msg);
            } else {
                reporter.printWarning(where.position(), msg);
            }
        } else {
            throw new IllegalStateException(msg);
        }
    }

    private void printError(Doc where, String msg) {
        if (reporter != null) {
            if (where == null) {
                reporter.printError(msg);
            } else {
                reporter.printError(where.position(), msg);
            }
        } else {
            throw new IllegalStateException(msg);
        }
    }

    static String xmlize(CharSequence text) {
        String noAmp = text.toString().replaceAll("&", "&amp;");
        String noZav = noAmp.toString().replaceAll("@", "&#064;");
        String noLt = noZav.replaceAll("<", "&lt;");
        String noGt = noLt.replaceAll(">", "&gt;");
        return noGt;
    }

    static String javaName(Path file1) {
        final String name = file1.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : null;
    }

    private static Pattern WORDS = Pattern.compile("\\w+");
    static String boldJavaKeywords(String text, Map<String,String> imports, Set<String> packages) {
        Matcher m = WORDS.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String append;
            switch (m.group(0)) {
                case "abstract":
                case "assert":
                case "boolean":
                case "break":
                case "byte":
                case "case":
                case "catch":
                case "class":
                case "const":
                case "continue":
                case "default":
                case "do":
                case "double":
                case "else":
                case "enum":
                case "extends":
                case "final":
                case "finally":
                case "float":
                case "for":
                case "goto":
                case "char":
                case "if":
                case "implements":
                case "import":
                case "instanceof":
                case "int":
                case "interface":
                case "long":
                case "native":
                case "new":
                case "package":
                case "private":
                case "protected":
                case "public":
                case "return":
                case "short":
                case "static":
                case "strictfp":
                case "super":
                case "switch":
                case "synchronized":
                case "this":
                case "throw":
                case "throws":
                case "transient":
                case "try":
                case "void":
                case "volatile":
                case "while":
                case "true":
                case "false":
                case "null":
                    append = "<b>" + m.group(0) + "</b>";
                    break;
                default:
                    String fqn;
                    fqn = imports.get(m.group(0));
                    if (fqn == null) {
                        fqn = tryLoad("java.lang", m.group(0));
                        if (fqn == null && packages != null) {
                            for (String p : packages) {
                                fqn = tryLoad(p, m.group(0));
                                if (fqn != null) {
                                    break;
                                }
                            }
                        }
                    }
                    if (fqn == null) {
                        append = m.group(0);
                    } else {
                        append = "{@link " + fqn + "}";
                    }
            }
            m.appendReplacement(sb, append);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String tryLoad(String pkg, String name) {
        try {
            String loaded = pkg + "." + name;
            Class.forName(loaded);
            return loaded;
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    static final int countChar(CharSequence seq, char ch) {
        int cnt = 0;
        for (int i = 0; i < seq.length(); i++) {
            if (ch == seq.charAt(i)) {
                cnt++;
            }
        }
        return cnt;
    }

    void setMaxLineLength(String maxLineLength) {
        if ( maxLineLength != null ) {
            try {
                this.maxLineLength = Integer.parseInt( maxLineLength );
            }
            catch (NumberFormatException ex) {

            }
        }
    }

    void setVerifySince(String sinceCheck) {
        this.verifySince = sinceCheck;
    }

    void addHiddenAnnotation(String fqn) {
        if (this.hiddenAnno == null) {
            this.hiddenAnno = new HashSet<>();
        }
        this.hiddenAnno.add(fqn);
    }

    boolean isHiddingAnnotation(String name) {
        return this.hiddenAnno != null && this.hiddenAnno.contains(name);
    }

    static int findMissingIndentation(String unclosedText) {
        int closed = 0;
        int i = unclosedText.length() - 1;
        while (i >= 0) {
            char ch = unclosedText.charAt(i--);
            if (ch == '}') {
                closed++;
            }
            if (ch == '{') {
                if (closed-- == 0) {
                    break;
                }
            }
        }
        int spaces = 0;
        while (i >= 0) {
            char ch = unclosedText.charAt(i--);
            if (ch == ' ') {
                spaces++;
                continue;
            }
            if (ch == '\n' || ch == '\r') {
                break;
            }
            spaces = 0;
        }
        return spaces;
    }

    private final class Item implements CharSequence {

        private StringBuilder sb = new StringBuilder();
        private int spaces = Integer.MAX_VALUE;
        private Stack<Integer> remove = new Stack<Integer>();
        private final Path file;

        public Item(Path file) {
            this.file = file;
        }

        public int length() {
            return sb.length();
        }

        public char charAt(int index) {
            return sb.charAt(index);
        }

        public CharSequence subSequence(int start, int end) {
            return sb.subSequence(start, end);
        }

        private void append(String line) {
            for (int sp = 0; sp < line.length(); sp++) {
                if (line.charAt(sp) != ' ') {
                    if (sp < spaces) {
                        spaces = sp;
                        break;
                    }
                }
            }
            remove.push(sb.length());
            sb.append(line);
            sb.append('\n');
        }

        public String toString(boolean finish, Map<String,String> imports, Set<String> packages) {
            final int len = maxLineLength;
            if (remove != null) {
                while (!remove.isEmpty()) {
                    Integer pos = remove.pop();
                    for (int i = 0; i < spaces; i++) {
                        if (sb.charAt(pos) == '\n') {
                            break;
                        }
                        sb.deleteCharAt(pos);
                    }
                }
                remove = null;

                int line = 0;
                for (int i = 0; i < sb.length(); i++) {
                    if (sb.charAt(i) == '\n') {
                        line = 0;
                        continue;
                    }
                    if (++line > len) {
                        printError(null, "Line is too long in: " + file + "\n" + sb);
                    }
                }

                int open = countChar(sb, '{');
                int end = countChar(sb, '}');
                if (finish) {
                    for (int i = 0; i < open - end; i++) {
                        int missingBraceIndent = findMissingIndentation(sb.toString());
                        while (missingBraceIndent-- > 0) {
                            sb.append(" ");
                        }
                        sb.append("}\n");
                    }
                }

                if (countChar(sb, '{') != countChar(sb, '}')) {
                    printError(null, "not paired amount of braces (consider using '// FINISH:' instead of '// END:') in " + file + "\n" + sb);
                }

            }
            String xml = xmlize(sb.toString());
            if (javaName(file) != null) {
                return boldJavaKeywords(xml, imports, packages);
            } else {
                return xml;
            }
        }
    } // end of Item}
}

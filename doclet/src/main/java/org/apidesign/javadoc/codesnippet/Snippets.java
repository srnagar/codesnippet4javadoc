/**
 * Codesnippet Javadoc Doclet
 * Copyright (C) 2015-2016 Jaroslav Tulach <jaroslav.tulach@apidesign.org>
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
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Snippets {
    private static final Pattern TAG = Pattern.compile("\\{ *@codesnippet *([\\.\\-a-z0-9A-Z]*) *\\}");
    private static final Pattern BEGIN = Pattern.compile(".* BEGIN: *(\\p{Graph}+)[-\\> ]*");
    private static final Pattern END = Pattern.compile(".* (END|FINISH): *(\\p{Graph}+)[-\\> ]*");
    private final DocErrorReporter reporter;
    private List<Path> search = new ArrayList<>();
    private Map<String,String> snippets;

    Snippets(DocErrorReporter reporter) {
        this.reporter = reporter;
    }

    void fixCodesnippets(Doc element) {
        final String txt = element.getRawCommentText();
        Matcher match = TAG.matcher(txt);
        for (;;) {
            if (!match.find()) {
                break;
            }
            final String code = "<pre>" + findSnippet(element, match.group(1)) + "</pre>";
            String newTxt = txt.substring(0, match.start(0)) +
                code +
                txt.substring(match.end(0));
            element.setRawCommentText(newTxt);
        }
        element.inlineTags();
    }

    String findSnippet(Doc element, String key) {
        if (snippets == null) {
            Map<String,String> tmp = new TreeMap<>();
            for (Path path : search) {
                if (!Files.isDirectory(path)) {
                    printWarning(null, "Cannot scan " + path + " not a directory!");
                    continue;
                }
                try {
                    scanDir(path, tmp);
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

    void addPath(Path path) {
        search.add(path);
    }

    private void scanDir(Path dir, final Map<String, String> collect) throws IOException {
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Map<String,CharSequence> texts = new TreeMap<>();
                try {
                    BufferedReader r = Files.newBufferedReader(file);
                    for (;;) {
                        String line = r.readLine();
                        if (line == null) {
                            break;
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
                                    texts.put(m.group(2), ((Item) s).toString(m.group(1).equals("FINISH")));
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
        String noLt = noAmp.replaceAll("<", "&lt;");
        String noGt = noLt.replaceAll(">", "&gt;");
        return noGt;
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

        @Override
        public String toString() {
            return toString(false);
        }

        public String toString(boolean finish) {
            final int len = 80;
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
                        sb.append("}\n");
                    }
                }

                if (countChar(sb, '{') != countChar(sb, '}')) {
                    printError(null, "not paired amount of braces in " + file + "\n" + sb);
                }

            }
            return colorify(sb, file);
        }

        private String colorify(StringBuilder text, Path file) {
            return xmlize(text.toString());
        }
    } // end of Item}
}
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

import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.LanguageVersion;
import com.sun.javadoc.RootDoc;
import com.sun.tools.doclets.formats.html.HtmlDoclet;

public final class Doclet {
    private Doclet() {
    }
    public static boolean start(RootDoc root) {
        return HtmlDoclet.start(root);
    }

    public static int optionLength(String option) {
        return HtmlDoclet.optionLength(option);
    }

    public static boolean validOptions(String[][] options, DocErrorReporter reporter) {
        return HtmlDoclet.validOptions(options, reporter);
    }

    public static LanguageVersion languageVersion() {
        return HtmlDoclet.languageVersion();
    }

}
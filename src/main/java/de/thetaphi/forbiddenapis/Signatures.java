/*
 * (C) Copyright Uwe Schindler (Generics Policeman) and others.
 * Parts of this work are licensed to the Apache Software Foundation (ASF)
 * under one or more contributor license agreements.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.thetaphi.forbiddenapis;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;

import de.thetaphi.forbiddenapis.Checker.Option;

/** Utility class that is used to get an overview of all fields and implemented
 * methods of a class. It make the signatures available as Sets. */
public final class Signatures implements Constants {
  
  private static final String BUNDLED_PREFIX = "@includeBundled ";
  private static final String DEFAULT_MESSAGE_PREFIX = "@defaultMessage ";
  private static final String IGNORE_UNRESOLVABLE_LINE = "@ignoreUnresolvable";

  private static enum UnresolvableReporting {
    FAIL(true) {
      @Override
      public void parseFailed(Logger logger, String message, String signature) throws ParseException {
        throw new ParseException(String.format(Locale.ENGLISH, "%s while parsing signature: %s", message, signature));
      }
    },
    WARNING(false) {
      @Override
      public void parseFailed(Logger logger, String message, String signature) throws ParseException {
        logger.warn(String.format(Locale.ENGLISH, "%s while parsing signature: %s [signature ignored]", message, signature));
      }
    },
    SILENT(true) {
      @Override
      public void parseFailed(Logger logger, String message, String signature) throws ParseException {
        // keep silent
      }
    };
    
    private UnresolvableReporting(boolean reportClassNotFound) {
      this.reportClassNotFound = reportClassNotFound;
    }
    
    public final boolean reportClassNotFound;
    public abstract void parseFailed(Logger logger, String message, String signature) throws ParseException;
  }
  
  private final RelatedClassLookup lookup;
  private final Logger logger;
  private final boolean failOnUnresolvableSignatures;
  private final boolean logMissingSignatures;

  /** Key is used to lookup forbidden signature in following formats:
   * <ul>
   * <li>methods: key is the internal name (slashed), followed by \000 and the method signature
   * <li>fields: key is the internal name (slashed), followed by \000 and the field name
   * <li>classes: key is the internal name (slashed)
   * </ul>
   */
  final Map<String,String> signatures = new HashMap<String,String>();
  
  /** set of patterns of forbidden classes */
  final Set<ClassPatternRule> classPatterns = new LinkedHashSet<ClassPatternRule>();
  
  /** if enabled, the bundled signature to enable heuristics for detection of non-portable runtime calls is used */
  private boolean forbidNonPortableRuntime = false;

  public Signatures(Checker checker) {
    this(checker, checker.logger, checker.options.contains(Option.FAIL_ON_UNRESOLVABLE_SIGNATURES), checker.options.contains(Option.LOG_MISSING_SIGNATURES));
  }
  
  public Signatures(RelatedClassLookup lookup, Logger logger, boolean failOnUnresolvableSignatures, boolean logMissingSignatures) {
    this.lookup = lookup;
    this.logger = logger;
    this.failOnUnresolvableSignatures = failOnUnresolvableSignatures;
    this.logMissingSignatures = logMissingSignatures;
  }
  
  static String getKey(String internalClassName) {
    return "c\000" + internalClassName;
  }
  
  static String getKey(String internalClassName, String field) {
    return "f\000" + internalClassName + '\000' + field;
  }
  
  static String getKey(String internalClassName, Method method) {
    return "m\000" + internalClassName + '\000' + method;
  }
  
  /** Adds the method signature to the list of disallowed methods. The Signature is checked against the given ClassLoader. */
  private void addSignature(final String line, final String defaultMessage, final UnresolvableReporting report, final Set<String> missingClasses) throws ParseException,IOException {
    final String clazz, field, signature;
    String message = null;
    final Method method;
    int p = line.indexOf('@');
    if (p >= 0) {
      signature = line.substring(0, p).trim();
      message = line.substring(p + 1).trim();
    } else {
      signature = line;
      message = defaultMessage;
    }
    if (line.isEmpty()) {
      throw new ParseException("Empty signature");
    }
    p = signature.indexOf('#');
    if (p >= 0) {
      clazz = signature.substring(0, p);
      final String s = signature.substring(p + 1);
      p = s.indexOf('(');
      if (p >= 0) {
        if (p == 0) {
          throw new ParseException("Invalid method signature (method name missing): " + signature);
        }
        // we ignore the return type, its just to match easier (so return type is void):
        try {
          method = Method.getMethod("void " + s, true);
        } catch (IllegalArgumentException iae) {
          throw new ParseException("Invalid method signature: " + signature);
        }
        field = null;
      } else {
        field = s;
        method = null;
      }
    } else {
      clazz = signature;
      method = null;
      field = null;
    }
    if (message != null && message.isEmpty()) {
      message = null;
    }
    // create printout message:
    final String printout = (message != null) ? (signature + " [" + message + "]") : signature;
    // check class & method/field signature, if it is really existent (in classpath), but we don't really load the class into JVM:
    if (AsmUtils.isGlob(clazz)) {
      if (method != null || field != null) {
        throw new ParseException(String.format(Locale.ENGLISH, "Class level glob pattern cannot be combined with methods/fields: %s", signature));
      }
      classPatterns.add(new ClassPatternRule(clazz, message));
    } else {
      final ClassSignature c;
      try {
        c = lookup.getClassFromClassLoader(clazz);
      } catch (ClassNotFoundException cnfe) {
        if (report.reportClassNotFound) {
          report.parseFailed(logger, String.format(Locale.ENGLISH, "Class '%s' not found on classpath", cnfe.getMessage()), signature);
        } else {
          missingClasses.add(clazz);
        }
        return;
      }
      if (method != null) {
        assert field == null;
        // list all methods with this signature:
        boolean found = false;
        for (final Method m : c.methods) {
          if (m.getName().equals(method.getName()) && Arrays.equals(m.getArgumentTypes(), method.getArgumentTypes())) {
            found = true;
            signatures.put(getKey(c.className, m), printout);
            // don't break when found, as there may be more covariant overrides!
          }
        }
        if (!found) {
          report.parseFailed(logger, "Method not found", signature);
          return;
        }
      } else if (field != null) {
        assert method == null;
        if (!c.fields.contains(field)) {
          report.parseFailed(logger, "Field not found", signature);
          return;
        }
        signatures.put(getKey(c.className, field), printout);
      } else {
        assert field == null && method == null;
        // only add the signature as class name
        signatures.put(getKey(c.className), printout);
      }
    }
  }
  
  private void reportMissingSignatureClasses(Set<String> missingClasses) {
    if (missingClasses.isEmpty() || !logMissingSignatures) {
      return;
    }
    logger.warn("Some signatures were ignored because the following classes were not found on classpath:");
    final StringBuilder sb = new StringBuilder();
    int count = 0;
    for (String s : missingClasses) {
      sb.append(count == 0 ? "  " : ", ").append(s);
      count++;
      if (sb.length() >= 70) {
        int remaining = missingClasses.size() - count;
        if (remaining > 0) {
          sb.append(",... (and ").append(remaining).append(" more).");
        }
        break;
      }
    }
    logger.warn(sb.toString());
  }

  private void addBundledSignatures(String name, String jdkTargetVersion, boolean logging, Set<String> missingClasses) throws IOException,ParseException {
    if (!name.matches("[A-Za-z0-9\\-\\.]+")) {
      throw new ParseException("Invalid bundled signature reference: " + name);
    }
    if (BS_JDK_NONPORTABLE.equals(name)) {
      if (logging) logger.info("Reading bundled API signatures: " + name);
      forbidNonPortableRuntime = true;
      return;
    }
    name = fixTargetVersion(name);
    // use Checker.class hardcoded (not getClass) so we have a fixed package name:
    InputStream in = Checker.class.getResourceAsStream("signatures/" + name + ".txt");
    // automatically expand the compiler version in here (for jdk-* signatures without version):
    if (in == null && jdkTargetVersion != null && name.startsWith("jdk-") && !name.matches(".*?\\-\\d+(\\.\\d+)*")) {
      name = name + "-" + jdkTargetVersion;
      name = fixTargetVersion(name);
      in = Checker.class.getResourceAsStream("signatures/" + name + ".txt");
    }
    if (in == null) {
      throw new FileNotFoundException("Bundled signatures resource not found: " + name);
    }
    if (logging) logger.info("Reading bundled API signatures: " + name);
    parseSignaturesStream(in, true, missingClasses);
  }
  
  private void parseSignaturesStream(InputStream in, boolean allowBundled, Set<String> missingClasses) throws IOException,ParseException {
    parseSignaturesFile(new InputStreamReader(in, "UTF-8"), allowBundled, missingClasses);
  }

  private void parseSignaturesFile(Reader reader, boolean isBundled, Set<String> missingClasses) throws IOException,ParseException {
    final BufferedReader r = new BufferedReader(reader);
    try {
      String line, defaultMessage = null;
      UnresolvableReporting reporter = failOnUnresolvableSignatures ? UnresolvableReporting.FAIL : UnresolvableReporting.WARNING;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.length() == 0 || line.startsWith("#"))
          continue;
        if (line.startsWith("@")) {
          if (isBundled && line.startsWith(BUNDLED_PREFIX)) {
            final String name = line.substring(BUNDLED_PREFIX.length()).trim();
            addBundledSignatures(name, null, false, missingClasses);
          } else if (line.startsWith(DEFAULT_MESSAGE_PREFIX)) {
            defaultMessage = line.substring(DEFAULT_MESSAGE_PREFIX.length()).trim();
            if (defaultMessage.length() == 0) defaultMessage = null;
          } else if (line.equals(IGNORE_UNRESOLVABLE_LINE)) {
            reporter = isBundled ? UnresolvableReporting.SILENT : UnresolvableReporting.WARNING;
          } else {
            throw new ParseException("Invalid line in signature file: " + line);
          }
        } else {
          addSignature(line, defaultMessage, reporter, missingClasses);
        }
      }
    } finally {
      r.close();
    }
  }
  
  /** Reads a list of bundled API signatures from classpath. */
  public void addBundledSignatures(String name, String jdkTargetVersion) throws IOException,ParseException {
    final Set<String> missingClasses = new TreeSet<String>();
    addBundledSignatures(name, jdkTargetVersion, true, missingClasses);
    reportMissingSignatureClasses(missingClasses);
  }
  
  /** Reads a list of API signatures. Closes the Reader when done (on Exception, too)! */
  public void parseSignaturesStream(InputStream in, String name) throws IOException,ParseException {
    logger.info("Reading API signatures: " + name);
    final Set<String> missingClasses = new TreeSet<String>();
    parseSignaturesStream(in, false, missingClasses);
    reportMissingSignatureClasses(missingClasses);
  }
  
  /** Reads a list of API signatures from a String. */
  public void parseSignaturesString(String signatures) throws IOException,ParseException {
    logger.info("Reading inline API signatures...");
    final Set<String> missingClasses = new TreeSet<String>();
    parseSignaturesFile(new StringReader(signatures), false, missingClasses);
    reportMissingSignatureClasses(missingClasses);
  }
  
  /** Returns if there are any signatures. */
  public boolean hasNoSignatures() {
    return 0 == signatures.size() + 
        classPatterns.size() +
        (forbidNonPortableRuntime ? 1 : 0);
  }
  
  /** Returns if bundled signature to enable heuristics for detection of non-portable runtime calls is used */
  public boolean isNonPortableRuntimeForbidden() {
    return this.forbidNonPortableRuntime;
  }
  
  public String checkType(Type type) {
    if (type.getSort() != Type.OBJECT) {
      return null; // we don't know this type, just pass!
    }
    final String printout = signatures.get(getKey(type.getInternalName()));
    if (printout != null) {
      return printout;
    }
    final String binaryClassName = type.getClassName();
    for (final ClassPatternRule r : classPatterns) {
      if (r.matches(binaryClassName)) {
        return r.getPrintout(binaryClassName);
      }
    }
    return null;
  }
  
  public String checkMethod(String internalClassName, Method method) {
    return signatures.get(getKey(internalClassName, method));
  }
  
  public String checkField(String internalClassName, String field) {
    return signatures.get(getKey(internalClassName, field));
  }
  
  public static String fixTargetVersion(String name) throws ParseException {
    final Matcher m = JDK_SIG_PATTERN.matcher(name);
    if (m.matches()) {
      if (m.group(4) == null) {
        final String prefix = m.group(1);
        final int major = Integer.parseInt(m.group(2));
        final int minor = m.group(3) != null ? Integer.parseInt(m.group(3).substring(1)) : 0;
        if (major == 1 && minor >= 1 && minor < 9) {
          // Java 1.1 till 1.8 (aka 8):
          return prefix + "1." + minor;
        } else if (major > 1 && major < 9) {
          // fix pre-Java9 major version to use "1.x" syntax:
          if (minor == 0) {
            return prefix + "1." + major;
          }
        } else if (major >= 9 && minor > 0) {
          return prefix + major + "." + minor;
        } else  if (major >= 9 && minor == 0) {
          return prefix + major;
        }
      }
      throw new ParseException("Invalid bundled signature reference (JDK version is invalid): " + name);
    }
    return name;
  }
  
}

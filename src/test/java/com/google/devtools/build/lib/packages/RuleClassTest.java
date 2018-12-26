// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.OUTPUT_LIST;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.substitutePlaceholderIntoTemplate;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;
import static com.google.devtools.build.lib.packages.RuleClass.NO_EXTERNAL_BINDINGS;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.INTEGER;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;
import static org.junit.Assert.fail;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.Location.LineAndColumn;
import com.google.devtools.build.lib.packages.Attribute.SkylarkComputedDefaultTemplate.CannotPrecomputeDefaultsException;
import com.google.devtools.build.lib.packages.Attribute.ValidityPredicate;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory;
import com.google.devtools.build.lib.packages.RuleClass.ExecutionPlatformConstraintsAllowed;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.util.PackageLoadingTestCase;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RuleClass}.
 */
@RunWith(JUnit4.class)
public class RuleClassTest extends PackageLoadingTestCase {
  private static final RuleClass.ConfiguredTargetFactory<Object, Object, Exception>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          new RuleClass.ConfiguredTargetFactory<Object, Object, Exception>() {
            @Override
            public Object create(Object ruleContext)
                throws InterruptedException, RuleErrorException, ActionConflictException {
              throw new IllegalStateException();
            }
          };

  private static final class DummyFragment extends BuildConfiguration.Fragment {

  }

  private static final Predicate<String> PREFERRED_DEPENDENCY_PREDICATE = Predicates.alwaysFalse();

  private static RuleClass createRuleClassA() throws LabelSyntaxException {
    return newRuleClass(
        "ruleA",
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        ImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        PredicatesWithMessage.<Rule>alwaysTrue(),
        PREFERRED_DEPENDENCY_PREDICATE,
        AdvertisedProviderSet.EMPTY,
        null,
        NO_EXTERNAL_BINDINGS,
        null,
        ImmutableSet.<Class<?>>of(),
        MissingFragmentPolicy.FAIL_ANALYSIS,
        true,
        attr("my-string-attr", STRING).mandatory().build(),
        attr("my-label-attr", LABEL)
            .mandatory()
            .legacyAllowAnyFileType()
            .value(Label.parseAbsolute("//default:label", ImmutableMap.of()))
            .build(),
        attr("my-labellist-attr", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
        attr("my-integer-attr", INTEGER).value(42).build(),
        attr("my-string-attr2", STRING).mandatory().value((String) null).build(),
        attr("my-stringlist-attr", STRING_LIST).build(),
        attr("my-sorted-stringlist-attr", STRING_LIST).orderIndependent().build());
  }

  private static RuleClass createRuleClassB(RuleClass ruleClassA) {
    // emulates attribute inheritance
    List<Attribute> attributes = new ArrayList<>(ruleClassA.getAttributes());
    attributes.add(attr("another-string-attr", STRING).mandatory().build());
    return newRuleClass(
        "ruleB",
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        ImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        PredicatesWithMessage.<Rule>alwaysTrue(),
        PREFERRED_DEPENDENCY_PREDICATE,
        AdvertisedProviderSet.EMPTY,
        null,
        NO_EXTERNAL_BINDINGS,
        null,
        ImmutableSet.<Class<?>>of(),
        MissingFragmentPolicy.FAIL_ANALYSIS,
        true,
        attributes.toArray(new Attribute[0]));
  }

  @Test
  public void testRuleClassBasics() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    assertThat(ruleClassA.getName()).isEqualTo("ruleA");
    assertThat(ruleClassA.getAttributeCount()).isEqualTo(7);

    assertThat((int) ruleClassA.getAttributeIndex("my-string-attr")).isEqualTo(0);
    assertThat((int) ruleClassA.getAttributeIndex("my-label-attr")).isEqualTo(1);
    assertThat((int) ruleClassA.getAttributeIndex("my-labellist-attr")).isEqualTo(2);
    assertThat((int) ruleClassA.getAttributeIndex("my-integer-attr")).isEqualTo(3);
    assertThat((int) ruleClassA.getAttributeIndex("my-string-attr2")).isEqualTo(4);
    assertThat((int) ruleClassA.getAttributeIndex("my-stringlist-attr")).isEqualTo(5);
    assertThat((int) ruleClassA.getAttributeIndex("my-sorted-stringlist-attr")).isEqualTo(6);

    assertThat(ruleClassA.getAttributeByName("my-string-attr"))
        .isEqualTo(ruleClassA.getAttribute(0));
    assertThat(ruleClassA.getAttributeByName("my-label-attr"))
        .isEqualTo(ruleClassA.getAttribute(1));
    assertThat(ruleClassA.getAttributeByName("my-labellist-attr"))
        .isEqualTo(ruleClassA.getAttribute(2));
    assertThat(ruleClassA.getAttributeByName("my-integer-attr"))
        .isEqualTo(ruleClassA.getAttribute(3));
    assertThat(ruleClassA.getAttributeByName("my-string-attr2"))
        .isEqualTo(ruleClassA.getAttribute(4));
    assertThat(ruleClassA.getAttributeByName("my-stringlist-attr"))
        .isEqualTo(ruleClassA.getAttribute(5));
    assertThat(ruleClassA.getAttributeByName("my-sorted-stringlist-attr"))
        .isEqualTo(ruleClassA.getAttribute(6));

    // default based on type
    assertThat(ruleClassA.getAttribute(0).getDefaultValue(null)).isEqualTo("");
    assertThat(ruleClassA.getAttribute(1).getDefaultValue(null))
        .isEqualTo(Label.parseAbsolute("//default:label", ImmutableMap.of()));
    assertThat(ruleClassA.getAttribute(2).getDefaultValue(null)).isEqualTo(Collections.emptyList());
    assertThat(ruleClassA.getAttribute(3).getDefaultValue(null)).isEqualTo(42);
    // default explicitly specified
    assertThat(ruleClassA.getAttribute(4).getDefaultValue(null)).isNull();
    assertThat(ruleClassA.getAttribute(5).getDefaultValue(null)).isEqualTo(Collections.emptyList());
    assertThat(ruleClassA.getAttribute(6).getDefaultValue(null)).isEqualTo(Collections.emptyList());
  }

  @Test
  public void testRuleClassInheritance() throws Exception {
    RuleClass ruleClassA = createRuleClassA();
    RuleClass ruleClassB = createRuleClassB(ruleClassA);

    assertThat(ruleClassB.getName()).isEqualTo("ruleB");
    assertThat(ruleClassB.getAttributeCount()).isEqualTo(8);

    assertThat((int) ruleClassB.getAttributeIndex("my-string-attr")).isEqualTo(0);
    assertThat((int) ruleClassB.getAttributeIndex("my-label-attr")).isEqualTo(1);
    assertThat((int) ruleClassB.getAttributeIndex("my-labellist-attr")).isEqualTo(2);
    assertThat((int) ruleClassB.getAttributeIndex("my-integer-attr")).isEqualTo(3);
    assertThat((int) ruleClassB.getAttributeIndex("my-string-attr2")).isEqualTo(4);
    assertThat((int) ruleClassB.getAttributeIndex("my-stringlist-attr")).isEqualTo(5);
    assertThat((int) ruleClassB.getAttributeIndex("my-sorted-stringlist-attr")).isEqualTo(6);
    assertThat((int) ruleClassB.getAttributeIndex("another-string-attr")).isEqualTo(7);

    assertThat(ruleClassB.getAttributeByName("my-string-attr"))
        .isEqualTo(ruleClassB.getAttribute(0));
    assertThat(ruleClassB.getAttributeByName("my-label-attr"))
        .isEqualTo(ruleClassB.getAttribute(1));
    assertThat(ruleClassB.getAttributeByName("my-labellist-attr"))
        .isEqualTo(ruleClassB.getAttribute(2));
    assertThat(ruleClassB.getAttributeByName("my-integer-attr"))
        .isEqualTo(ruleClassB.getAttribute(3));
    assertThat(ruleClassB.getAttributeByName("my-string-attr2"))
        .isEqualTo(ruleClassB.getAttribute(4));
    assertThat(ruleClassB.getAttributeByName("my-stringlist-attr"))
        .isEqualTo(ruleClassB.getAttribute(5));
    assertThat(ruleClassB.getAttributeByName("my-sorted-stringlist-attr"))
        .isEqualTo(ruleClassB.getAttribute(6));
    assertThat(ruleClassB.getAttributeByName("another-string-attr"))
        .isEqualTo(ruleClassB.getAttribute(7));
  }

  private static final String TEST_PACKAGE_NAME = "testpackage";

  private static final String TEST_RULE_NAME = "my-rule-A";

  private static final int TEST_RULE_DEFINED_AT_LINE = 42;

  private static final String TEST_RULE_LABEL = "@//" + TEST_PACKAGE_NAME + ":" + TEST_RULE_NAME;

  private Path testBuildfilePath;
  private Location testRuleLocation;

  @Before
  public final void setRuleLocation() throws Exception {
    testBuildfilePath = root.getRelative("testpackage/BUILD");
    testRuleLocation = Location.fromPathAndStartColumn(
        testBuildfilePath.asFragment(), 0, 0, new LineAndColumn(TEST_RULE_DEFINED_AT_LINE, 0));
  }

  private Package.Builder createDummyPackageBuilder() {
    return packageFactory
        .newPackageBuilder(PackageIdentifier.createInMainRepo(TEST_PACKAGE_NAME), "TESTING")
        .setFilename(RootedPath.toRootedPath(root, testBuildfilePath));
  }

  @Test
  public void testDuplicatedDeps() throws Exception {
    RuleClass depsRuleClass =
        newRuleClass(
            "ruleDeps",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true,
            attr("list1", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
            attr("list2", LABEL_LIST).mandatory().legacyAllowAnyFileType().build(),
            attr("list3", LABEL_LIST).mandatory().legacyAllowAnyFileType().build());

    // LinkedHashMap -> predictable iteration order for testing
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("list1", Lists.newArrayList("//testpackage:dup1", ":dup1", ":nodup"));
    attributeValues.put("list2", Lists.newArrayList(":nodup1", ":nodup2"));
    attributeValues.put("list3", Lists.newArrayList(":dup1", ":dup1", ":dup2", ":dup2"));

    reporter.removeHandler(failFastHandler);
    createRule(depsRuleClass, "depsRule", attributeValues, testRuleLocation);

    assertThat(eventCollector.count()).isSameAs(3);
    assertDupError("//testpackage:dup1", "list1", "depsRule");
    assertDupError("//testpackage:dup1", "list3", "depsRule");
    assertDupError("//testpackage:dup2", "list3", "depsRule");
  }

  private void assertDupError(String label, String attrName, String ruleName) {
    assertContainsEvent(String.format("Label '%s' is duplicated in the '%s' attribute of rule '%s'",
        label, attrName, ruleName));
  }

  @Test
  public void testCreateRuleWithLegacyPublicVisibility() throws Exception {
    RuleClass ruleClass =
        newRuleClass(
            "ruleVis",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true,
            attr("visibility", LABEL_LIST).legacyAllowAnyFileType().build());
    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("visibility", Arrays.asList("//visibility:legacy_public"));

    reporter.removeHandler(failFastHandler);
    EventCollector collector = new EventCollector(EventKind.ERRORS);
    reporter.addHandler(collector);

    createRule(ruleClass, TEST_RULE_NAME, attributeValues, testRuleLocation);

    assertContainsEvent("//visibility:legacy_public only allowed in package declaration");
  }

  @Test
  public void testCreateRule() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    // LinkedHashMap -> predictable iteration order for testing
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("my-labellist-attr", "foobar"); // wrong type
    attributeValues.put("bogus-attr", "foobar"); // no such attr
    attributeValues.put("my-stringlist-attr", Arrays.asList("foo", "bar"));

    reporter.removeHandler(failFastHandler);
    EventCollector collector = new EventCollector(EventKind.ERRORS);
    reporter.addHandler(collector);

    Rule rule = createRule(ruleClassA, TEST_RULE_NAME, attributeValues, testRuleLocation);

    // TODO(blaze-team): (2009) refactor to use assertContainsEvent
    Iterator<String> expectedMessages = Arrays.asList(
        "expected value of type 'list(label)' for attribute 'my-labellist-attr' "
        + "in 'ruleA' rule, but got \"foobar\" (string)",
        "no such attribute 'bogus-attr' in 'ruleA' rule",
        "missing value for mandatory "
        + "attribute 'my-string-attr' in 'ruleA' rule",
        "missing value for mandatory attribute 'my-label-attr' in 'ruleA' rule",
        "missing value for mandatory "
        + "attribute 'my-labellist-attr' in 'ruleA' rule",
        "missing value for mandatory "
        + "attribute 'my-string-attr2' in 'ruleA' rule"
    ).iterator();

    for (Event event : collector) {
      assertThat(event.getLocation().getStartLineAndColumn().getLine())
          .isEqualTo(TEST_RULE_DEFINED_AT_LINE);
      assertThat(event.getLocation().getPath()).isEqualTo(testBuildfilePath.asFragment());
      assertThat(event.getMessage())
          .isEqualTo(TEST_RULE_LABEL.toString().substring(1) + ": " + expectedMessages.next());
    }

    // Test basic rule properties:
    assertThat(rule.getRuleClass()).isEqualTo("ruleA");
    assertThat(rule.getName()).isEqualTo(TEST_RULE_NAME);
    assertThat(rule.getLabel().toString()).isEqualTo(TEST_RULE_LABEL.substring(1));

    // Test attribute access:
    AttributeMap attributes = RawAttributeMapper.of(rule);
    assertThat(attributes.get("my-label-attr", BuildType.LABEL).toString())
        .isEqualTo("//default:label");
    assertThat(attributes.get("my-integer-attr", Type.INTEGER).intValue()).isEqualTo(42);
    // missing attribute -> default chosen based on type
    assertThat(attributes.get("my-string-attr", Type.STRING)).isEmpty();
    assertThat(attributes.get("my-labellist-attr", BuildType.LABEL_LIST)).isEmpty();
    assertThat(attributes.get("my-stringlist-attr", Type.STRING_LIST))
        .isEqualTo(Arrays.asList("foo", "bar"));
    try {
      attributes.get("my-labellist-attr", Type.STRING); // wrong type
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Attribute my-labellist-attr is of type list(label) "
          + "and not of type string in ruleA rule //testpackage:my-rule-A");
    }
  }

  @Test
  public void testImplicitOutputs() throws Exception {
    RuleClass ruleClassC =
        newRuleClass(
            "ruleC",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates(
                "foo-%{name}.bar", "lib%{name}-wazoo-%{name}.mumble", "stuff-%{outs}-bar"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true,
            attr("name", STRING).build(),
            attr("outs", OUTPUT_LIST).build());

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("outs", Collections.singletonList("explicit_out"));
    attributeValues.put("name", "myrule");

    Rule rule = createRule(ruleClassC, "myrule", attributeValues, testRuleLocation);

    Set<String> set = new HashSet<>();
    for (OutputFile outputFile : rule.getOutputFiles()) {
      set.add(outputFile.getName());
      assertThat(outputFile.getGeneratingRule()).isSameAs(rule);
    }
    assertThat(set).containsExactly("foo-myrule.bar", "libmyrule-wazoo-myrule.mumble",
        "stuff-explicit_out-bar", "explicit_out");
  }

  @Test
  public void testImplicitOutsWithBasenameDirname() throws Exception {
    RuleClass ruleClass =
        newRuleClass(
            "ruleClass",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates("%{dirname}lib%{basename}.bar"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true);

    Rule rule = createRule(ruleClass, "myRule", Collections.<String, Object>emptyMap(),
        testRuleLocation);
    assertThat(Iterables.getOnlyElement(rule.getOutputFiles()).getName())
        .isEqualTo("libmyRule.bar");

    Rule ruleWithSlash = createRule(ruleClass, "myRule/with/slash",
        Collections.<String, Object>emptyMap(), testRuleLocation);
    assertThat(Iterables.getOnlyElement(ruleWithSlash.getOutputFiles()).getName())
        .isEqualTo("myRule/with/libslash.bar");
  }

  /**
   * Helper routine that instantiates a rule class with the given computed default and supporting
   * attributes for the default to reference.
   */
  private static RuleClass getRuleClassWithComputedDefault(Attribute computedDefault) {
    return newRuleClass(
        "ruleClass",
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        ImplicitOutputsFunction.fromTemplates("empty"),
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        PredicatesWithMessage.<Rule>alwaysTrue(),
        PREFERRED_DEPENDENCY_PREDICATE,
        AdvertisedProviderSet.EMPTY,
        null,
        NO_EXTERNAL_BINDINGS,
        null,
        ImmutableSet.<Class<?>>of(),
        MissingFragmentPolicy.FAIL_ANALYSIS,
        true,
        attr("condition", BOOLEAN).value(false).build(),
        attr("declared1", BOOLEAN).value(false).build(),
        attr("declared2", BOOLEAN).value(false).build(),
        attr("nonconfigurable", BOOLEAN).nonconfigurable("test").value(false).build(),
        computedDefault);
  }

  /**
   * Helper routine that checks that a computed default is valid and bound to the expected value.
   */
  private void checkValidComputedDefault(Object expectedValue, Attribute computedDefault,
      ImmutableMap<String, Object> attrValueMap) throws Exception {
    assertThat(computedDefault.getDefaultValueUnchecked())
        .isInstanceOf(Attribute.ComputedDefault.class);
    Rule rule = createRule(getRuleClassWithComputedDefault(computedDefault), "myRule",
        attrValueMap, testRuleLocation);
    AttributeMap attributes = RawAttributeMapper.of(rule);
    assertThat(attributes.get(computedDefault.getName(), computedDefault.getType()))
        .isEqualTo(expectedValue);
  }

  /**
   * Helper routine that checks that a computed default is invalid due to declared dependency
   * issues and fails with the expected message.
   */
  private void checkInvalidComputedDefault(Attribute computedDefault, String expectedMessage)
      throws Exception {
    try {
      createRule(getRuleClassWithComputedDefault(computedDefault), "myRule",
              ImmutableMap.<String, Object>of(), testRuleLocation);
      fail("Expected computed default \"" + computedDefault.getName() + "\" to fail with "
          + "declaration errors");
    } catch (IllegalArgumentException e) {
      // Expected outcome.
      assertThat(e).hasMessage(expectedMessage);
    }
  }

  /**
   * Tests computed default values are computed as expected.
   */
  @Test
  public void testComputedDefault() throws Exception {
    Attribute computedDefault =
        attr("$result", BOOLEAN).value(new Attribute.ComputedDefault("condition") {
          @Override
          public Object getDefault(AttributeMap rule) {
            return rule.get("condition", Type.BOOLEAN);
          }
        }).build();

    checkValidComputedDefault(Boolean.FALSE, computedDefault,
        ImmutableMap.<String, Object>of("condition", Boolean.FALSE));
    checkValidComputedDefault(Boolean.TRUE, computedDefault,
        ImmutableMap.<String, Object>of("condition", Boolean.TRUE));
  }

  /**
   * Tests that computed defaults can only read attribute values for configurable attributes that
   * have been explicitly declared.
   */
  @Test
  public void testComputedDefaultDeclarations() throws Exception {
    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_no_declares", BOOLEAN).value(
            new Attribute.ComputedDefault() {
              @Override public Object getDefault(AttributeMap rule) {
                // OK: not a value check:
                return rule.isAttributeValueExplicitlySpecified("undeclared");
              }
        }).build(),
        ImmutableMap.<String, Object>of());

    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_one_declare", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN);
              }
        }).build(),
        ImmutableMap.<String, Object>of());

    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_two_declares", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1", "declared2") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN) && rule.get("declared2", Type.BOOLEAN);
              }
        }).build(),
        ImmutableMap.<String, Object>of());

    checkInvalidComputedDefault(
        attr("$bad_default_no_declares", BOOLEAN).value(
            new Attribute.ComputedDefault() {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"declared1\" isn't available in this computed default context");

    checkInvalidComputedDefault(
        attr("$bad_default_one_declare", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("declared1", Type.BOOLEAN) || rule.get("declared2", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"declared2\" isn't available in this computed default context");

    checkInvalidComputedDefault(
        attr("$bad_default_two_declares", BOOLEAN).value(
            new Attribute.ComputedDefault("declared1", "declared2") {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("condition", Type.BOOLEAN);
              }
        }).build(),
        "attribute \"condition\" isn't available in this computed default context");
  }

  /**
   * Tests that computed defaults *can* read attribute values for non-configurable attributes
   * without needing to explicitly declare them.
   */
  @Test
  public void testComputedDefaultWithNonConfigurableAttributes() throws Exception {
    checkValidComputedDefault(
        Boolean.FALSE,
        attr("$good_default_reading_undeclared_nonconfigurable_attribute", BOOLEAN).value(
            new Attribute.ComputedDefault() {
              @Override public Object getDefault(AttributeMap rule) {
                return rule.get("nonconfigurable", Type.BOOLEAN);
              }
        }).build(),
        ImmutableMap.<String, Object>of());
  }

  @Test
  public void testOutputsAreOrdered() throws Exception {
    RuleClass ruleClassC =
        newRuleClass(
            "ruleC",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.fromTemplates("first-%{name}", "second-%{name}", "out-%{outs}"),
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true,
            attr("name", STRING).build(),
            attr("outs", OUTPUT_LIST).build());

    Map<String, Object> attributeValues = new HashMap<>();
    attributeValues.put("outs", ImmutableList.of("third", "fourth"));
    attributeValues.put("name", "myrule");

    Rule rule = createRule(ruleClassC, "myrule", attributeValues, testRuleLocation);

    List<String> actual = new ArrayList<>();
    for (OutputFile outputFile : rule.getOutputFiles()) {
      actual.add(outputFile.getName());
      assertThat(outputFile.getGeneratingRule()).isSameAs(rule);
    }
    assertWithMessage("unexpected output set").that(actual).containsExactly("first-myrule",
        "second-myrule", "out-third", "out-fourth", "third", "fourth");
    assertWithMessage("invalid output ordering").that(actual).containsExactly("first-myrule",
        "second-myrule", "out-third", "out-fourth", "third", "fourth").inOrder();
  }

  @Test
  public void testSubstitutePlaceholderIntoTemplate() throws Exception {
    RuleClass ruleClass =
        newRuleClass(
            "ruleA",
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            ImplicitOutputsFunction.NONE,
            null,
            DUMMY_CONFIGURED_TARGET_FACTORY,
            PredicatesWithMessage.<Rule>alwaysTrue(),
            PREFERRED_DEPENDENCY_PREDICATE,
            AdvertisedProviderSet.EMPTY,
            null,
            NO_EXTERNAL_BINDINGS,
            null,
            ImmutableSet.<Class<?>>of(),
            MissingFragmentPolicy.FAIL_ANALYSIS,
            true,
            attr("a", STRING_LIST).mandatory().build(),
            attr("b", STRING_LIST).mandatory().build(),
            attr("c", STRING_LIST).mandatory().build(),
            attr("baz", STRING_LIST).mandatory().build(),
            attr("empty", STRING_LIST).build());

    Map<String, Object> attributeValues = new LinkedHashMap<>();
    attributeValues.put("a", ImmutableList.of("a", "A"));
    attributeValues.put("b", ImmutableList.of("b", "B"));
    attributeValues.put("c", ImmutableList.of("c", "C"));
    attributeValues.put("baz", ImmutableList.of("baz", "BAZ"));
    attributeValues.put("empty", ImmutableList.<String>of());

    AttributeMap rule = RawAttributeMapper.of(
        createRule(ruleClass, "testrule", attributeValues, testRuleLocation));

    assertThat(substitutePlaceholderIntoTemplate("foo", rule)).containsExactly("foo");
    assertThat(substitutePlaceholderIntoTemplate("foo-%{baz}-bar", rule)).containsExactly(
        "foo-baz-bar", "foo-BAZ-bar").inOrder();
    assertThat(substitutePlaceholderIntoTemplate("%{a}-%{b}-%{c}", rule)).containsExactly("a-b-c",
        "a-b-C", "a-B-c", "a-B-C", "A-b-c", "A-b-C", "A-B-c", "A-B-C").inOrder();
    assertThat(substitutePlaceholderIntoTemplate("%{a", rule)).containsExactly("%{a");
    assertThat(substitutePlaceholderIntoTemplate("%{a}}", rule)).containsExactly("a}", "A}")
        .inOrder();
    assertThat(substitutePlaceholderIntoTemplate("x%{a}y%{empty}", rule)).isEmpty();
  }

  @Test
  public void testOrderIndependentAttribute() throws Exception {
    RuleClass ruleClassA = createRuleClassA();

    List<String> list = Arrays.asList("foo", "bar", "baz");
    Map<String, Object> attributeValues = new LinkedHashMap<>();
    // mandatory values
    attributeValues.put("my-string-attr", "");
    attributeValues.put("my-label-attr", "//project");
    attributeValues.put("my-string-attr2", "");
    attributeValues.put("my-labellist-attr", Collections.emptyList());
    // to compare the effect of .orderIndependent()
    attributeValues.put("my-stringlist-attr", list);
    attributeValues.put("my-sorted-stringlist-attr", list);

    Rule rule = createRule(ruleClassA, "testrule", attributeValues, testRuleLocation);
    AttributeMap attributes = RawAttributeMapper.of(rule);

    assertThat(attributes.get("my-stringlist-attr", Type.STRING_LIST)).isEqualTo(list);
    assertThat(attributes.get("my-sorted-stringlist-attr", Type.STRING_LIST))
        .isEqualTo(Arrays.asList("bar", "baz", "foo"));
  }

  private Rule createRule(
      RuleClass ruleClass, String name, Map<String, Object> attributeValues, Location location)
      throws LabelSyntaxException, InterruptedException, CannotPrecomputeDefaultsException {
    Package.Builder pkgBuilder = createDummyPackageBuilder();
    Label ruleLabel;
    try {
      ruleLabel = pkgBuilder.createLabel(name);
    } catch (LabelSyntaxException e) {
      throw new IllegalArgumentException("Rule has illegal label", e);
    }
    return ruleClass.createRule(
        pkgBuilder,
        ruleLabel,
        new BuildLangTypedAttributeValuesMap(attributeValues),
        reporter,
        /*ast=*/ null,
        location,
        new AttributeContainer(ruleClass));
  }

  @Test
  public void testOverrideWithWrongType() {
    try {
      RuleClass parentRuleClass = createParentRuleClass();

      RuleClass.Builder childRuleClassBuilder = new RuleClass.Builder(
          "child_rule", RuleClassType.NORMAL, false, parentRuleClass);
      childRuleClassBuilder.override(attr("attr", INTEGER));
      fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("The type of the new attribute 'int' is different from "
          + "the original one 'string'.");
    }
  }

  @Test
  public void testOverrideWithRightType() {
    RuleClass parentRuleClass = createParentRuleClass();

    RuleClass.Builder childRuleClassBuilder = new RuleClass.Builder(
      "child_rule", RuleClassType.NORMAL, false, parentRuleClass);
      childRuleClassBuilder.override(attr("attr", STRING));
  }

  @Test
  public void testCopyAndOverrideAttribute() throws Exception {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);

    Map<String, Object> parentValues = new LinkedHashMap<>();
    Map<String, Object> childValues = new LinkedHashMap<>();
    childValues.put("attr", "somevalue");
    createRule(parentRuleClass, "parent_rule", parentValues, testRuleLocation);
    createRule(childRuleClass, "child_rule", childValues, testRuleLocation);
  }

  @Test
  public void testCopyAndOverrideAttributeMandatoryMissing() throws Exception {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);

    Map<String, Object> childValues = new LinkedHashMap<>();
    reporter.removeHandler(failFastHandler);
    createRule(childRuleClass, "child_rule", childValues, testRuleLocation);

    assertThat(eventCollector.count()).isSameAs(1);
    assertContainsEvent("//testpackage:child_rule: missing value for mandatory "
        + "attribute 'attr' in 'child_rule' rule");
  }

  @Test
  public void testRequiredFragmentInheritance() throws Exception {
    RuleClass parentRuleClass = createParentRuleClass();
    RuleClass childRuleClass = createChildRuleClass(parentRuleClass);
    assertThat(parentRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
        .containsExactly(DummyFragment.class);
    assertThat(childRuleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments())
        .containsExactly(DummyFragment.class);
  }

  private static RuleClass newRuleClass(
      String name,
      boolean skylarkExecutable,
      boolean documented,
      boolean publicByDefault,
      boolean binaryOutput,
      boolean workspaceOnly,
      boolean outputsDefaultExecutable,
      boolean isAnalysisTest,
      ImplicitOutputsFunction implicitOutputsFunction,
      RuleTransitionFactory transitionFactory,
      ConfiguredTargetFactory<?, ?, ?> configuredTargetFactory,
      PredicateWithMessage<Rule> validityPredicate,
      Predicate<String> preferredDependencyPredicate,
      AdvertisedProviderSet advertisedProviders,
      @Nullable BaseFunction configuredTargetFunction,
      Function<? super Rule, Map<String, Label>> externalBindingsFunction,
      @Nullable Environment ruleDefinitionEnvironment,
      Set<Class<?>> allowedConfigurationFragments,
      MissingFragmentPolicy missingFragmentPolicy,
      boolean supportsConstraintChecking,
      Attribute... attributes) {
    String ruleDefinitionEnvironmentHashCode =
        ruleDefinitionEnvironment == null
            ? null
            : ruleDefinitionEnvironment.getTransitiveContentHashCode();
    return new RuleClass(
        name,
        name,
        RuleClassType.NORMAL,
        /*isSkylark=*/ skylarkExecutable,
        /*skylarkTestable=*/ false,
        documented,
        publicByDefault,
        binaryOutput,
        workspaceOnly,
        outputsDefaultExecutable,
        isAnalysisTest,
        /* hasFunctionTransitionWhitelist=*/ false,
        implicitOutputsFunction,
        /*isConfigMatcher=*/ false,
        transitionFactory,
        configuredTargetFactory,
        validityPredicate,
        preferredDependencyPredicate,
        advertisedProviders,
        configuredTargetFunction,
        externalBindingsFunction,
        /*optionReferenceFunction=*/ RuleClass.NO_OPTION_REFERENCE,
        ruleDefinitionEnvironment == null
            ? null
            : ruleDefinitionEnvironment.getGlobals().getLabel(),
        ruleDefinitionEnvironmentHashCode,
        new ConfigurationFragmentPolicy.Builder()
            .requiresConfigurationFragments(allowedConfigurationFragments)
            .setMissingFragmentPolicy(missingFragmentPolicy)
            .build(),
        supportsConstraintChecking,
        /*requiredToolchains=*/ ImmutableSet.of(),
        /*supportsPlatforms=*/ true,
        ExecutionPlatformConstraintsAllowed.PER_RULE,
        /* executionPlatformConstraints= */ ImmutableSet.of(),
        OutputFile.Kind.FILE,
        ImmutableList.copyOf(attributes),
        /* buildSetting= */ null);
  }

  private static RuleClass createParentRuleClass() {
    return newRuleClass(
        "parent_rule",
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        ImplicitOutputsFunction.NONE,
        null,
        DUMMY_CONFIGURED_TARGET_FACTORY,
        PredicatesWithMessage.<Rule>alwaysTrue(),
        PREFERRED_DEPENDENCY_PREDICATE,
        AdvertisedProviderSet.EMPTY,
        null,
        NO_EXTERNAL_BINDINGS,
        null,
        ImmutableSet.<Class<?>>of(DummyFragment.class),
        MissingFragmentPolicy.FAIL_ANALYSIS,
        true,
        attr("attr", STRING).build());
  }

  private static RuleClass createChildRuleClass(RuleClass parentRuleClass) {
    RuleClass.Builder childRuleClassBuilder = new RuleClass.Builder(
        "child_rule", RuleClassType.NORMAL, false, parentRuleClass);
    return childRuleClassBuilder.override(
        childRuleClassBuilder
          .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
          .copy("attr").mandatory())
          .add(attr("tags", STRING_LIST))
          .build();
  }

  @Test
  public void testValidityChecker() throws Exception {
    RuleClass depClass = new RuleClass.Builder("dep", RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(attr("tags", STRING_LIST))
        .build();
    final Rule dep1 = createRule(depClass, "dep1", Collections.<String, Object>emptyMap(),
        testRuleLocation);
    final Rule dep2 = createRule(depClass, "dep2", Collections.<String, Object>emptyMap(),
        testRuleLocation);

    ValidityPredicate checker =
        new ValidityPredicate() {
          @Override
          public String checkValid(Rule from, Rule to) {
            assertThat(from.getName()).isEqualTo("top");
            if (to.getName().equals("dep1")) {
              return "pear";
            } else if (to.getName().equals("dep2")) {
              return null;
            } else {
              fail("invalid dependency");
              return null;
            }
          }
        };

    RuleClass topClass = new RuleClass.Builder("top", RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(attr("tags", STRING_LIST))
        .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType()
              .validityPredicate(checker))
        .build();

    Rule topRule = createRule(topClass, "top", Collections.<String, Object>emptyMap(),
        testRuleLocation);

    assertThat(topClass.getAttributeByName("deps").getValidityPredicate().checkValid(topRule, dep1))
        .isEqualTo("pear");
    assertThat(topClass.getAttributeByName("deps").getValidityPredicate().checkValid(topRule, dep2))
        .isNull();
  }

  /**
   * Tests structure for making certain rules "preferential choices" for certain files
   * under --compile_one_dependency.
   */
  @Test
  public void testPreferredDependencyChecker() throws Exception {
    final String cppFile = "file.cc";
    final String textFile = "file.txt";

    // Default: not preferred for anything.
    RuleClass defaultClass = new RuleClass.Builder("defaultClass", RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(attr("tags", STRING_LIST))
        .build();
    final Rule defaultRule = createRule(defaultClass, "defaultRule",
        Collections.<String, Object>emptyMap(), testRuleLocation);
    assertThat(defaultRule.getRuleClassObject().isPreferredDependency(cppFile)).isFalse();
    assertThat(defaultRule.getRuleClassObject().isPreferredDependency(textFile)).isFalse();

    // Make a rule that's preferred for C++ sources.
    RuleClass cppClass = new RuleClass.Builder("cppClass", RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(attr("tags", STRING_LIST))
        .setPreferredDependencyPredicate(new Predicate<String>() {
          @Override
          public boolean apply(String filename) {
            return filename.endsWith(".cc");
          }
        })
        .build();
    final Rule cppRule = createRule(cppClass, "cppRule",
        Collections.<String, Object>emptyMap(), testRuleLocation);
    assertThat(cppRule.getRuleClassObject().isPreferredDependency(cppFile)).isTrue();
    assertThat(cppRule.getRuleClassObject().isPreferredDependency(textFile)).isFalse();
  }

  @Test
  public void testBadRuleClassNames() {
    expectError(RuleClassType.NORMAL, "8abc");
    expectError(RuleClassType.NORMAL, "!abc");
    expectError(RuleClassType.NORMAL, "a b");
  }

  private void expectError(RuleClassType type, String name) {
    try {
      type.checkName(name);
      fail();
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testRequiredToolchains() throws Exception {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));

    ruleClassBuilder.addRequiredToolchains(
        Label.parseAbsolute("//toolchain:tc1", ImmutableMap.of()),
        Label.parseAbsolute("//toolchain:tc2", ImmutableMap.of()));

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass.getRequiredToolchains())
        .containsExactly(
            Label.parseAbsolute("//toolchain:tc1", ImmutableMap.of()),
            Label.parseAbsolute("//toolchain:tc2", ImmutableMap.of()));
  }

  @Test
  public void testExecutionPlatformConstraints_perRule() throws Exception {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE);

    ruleClassBuilder.addExecutionPlatformConstraints(
        Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
        Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()));

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
            Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()));
    assertThat(ruleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isFalse();
  }

  @Test
  public void testExecutionPlatformConstraints_perTarget() {
    RuleClass.Builder ruleClassBuilder =
        new RuleClass.Builder("ruleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST));

    ruleClassBuilder.executionPlatformConstraintsAllowed(
        ExecutionPlatformConstraintsAllowed.PER_TARGET);

    RuleClass ruleClass = ruleClassBuilder.build();

    assertThat(ruleClass.executionPlatformConstraintsAllowed())
        .isEqualTo(ExecutionPlatformConstraintsAllowed.PER_TARGET);
    assertThat(ruleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isTrue();
  }

  @Test
  public void testExecutionPlatformConstraints_inheritConstraintsFromParent() throws Exception {
    RuleClass parentRuleClass =
        new RuleClass.Builder("$parentRuleClass", RuleClassType.ABSTRACT, false)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE)
            .addExecutionPlatformConstraints(
                Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
                Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()))
            .build();

    RuleClass childRuleClass =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .build();

    assertThat(childRuleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
            Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()));
    assertThat(childRuleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isFalse();
  }

  @Test
  public void testExecutionPlatformConstraints_inheritAndAddConstraints() throws Exception {
    RuleClass parentRuleClass =
        new RuleClass.Builder("$parentRuleClass", RuleClassType.ABSTRACT, false)
            .add(attr("tags", STRING_LIST))
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE)
            .addExecutionPlatformConstraints(
                Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
                Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()));

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.getExecutionPlatformConstraints())
        .containsExactly(
            Label.parseAbsolute("//constraints:cv1", ImmutableMap.of()),
            Label.parseAbsolute("//constraints:cv2", ImmutableMap.of()));
    assertThat(childRuleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isFalse();
  }

  @Test
  public void testExecutionPlatformConstraints_inherit_parentAllowsPerTarget() {
    RuleClass parentRuleClass =
        new RuleClass.Builder("parentRuleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET)
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY);

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.executionPlatformConstraintsAllowed())
        .isEqualTo(ExecutionPlatformConstraintsAllowed.PER_TARGET);
    assertThat(childRuleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isTrue();
  }

  @Test
  public void testExecutionPlatformConstraints_inherit_multipleParents() {
    RuleClass parentRuleClass1 =
        new RuleClass.Builder("parentRuleClass1", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET)
            .build();
    RuleClass parentRuleClass2 =
        new RuleClass.Builder("$parentRuleClass2", RuleClassType.ABSTRACT, false)
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE)
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder(
                "childRuleClass", RuleClassType.NORMAL, false, parentRuleClass1, parentRuleClass2)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY);

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.executionPlatformConstraintsAllowed())
        .isEqualTo(ExecutionPlatformConstraintsAllowed.PER_TARGET);
    assertThat(childRuleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isTrue();
  }

  @Test
  public void testExecutionPlatformConstraints_inherit_parentAllowsPerTarget_override() {
    RuleClass parentRuleClass =
        new RuleClass.Builder("parentRuleClass", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET)
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE);

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.executionPlatformConstraintsAllowed())
        .isEqualTo(ExecutionPlatformConstraintsAllowed.PER_RULE);
  }

  @Test
  public void testExecutionPlatformConstraints_inherit_childAllowsPerTarget() {
    RuleClass parentRuleClass =
        new RuleClass.Builder("$parentRuleClass", RuleClassType.ABSTRACT, false)
            .add(attr("tags", STRING_LIST))
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_RULE)
            .build();

    RuleClass.Builder childRuleClassBuilder =
        new RuleClass.Builder("childRuleClass", RuleClassType.NORMAL, false, parentRuleClass)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .executionPlatformConstraintsAllowed(ExecutionPlatformConstraintsAllowed.PER_TARGET);

    RuleClass childRuleClass = childRuleClassBuilder.build();

    assertThat(childRuleClass.executionPlatformConstraintsAllowed())
        .isEqualTo(ExecutionPlatformConstraintsAllowed.PER_TARGET);
    assertThat(childRuleClass.hasAttr("exec_compatible_with", LABEL_LIST)).isTrue();
  }

  @Test
  public void testBuildSetting_createsDefaultAttribute() {
    RuleClass labelFlag =
        new RuleClass.Builder("label_flag", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .setBuildSetting(new BuildSetting(true, LABEL))
            .build();
    RuleClass stringSetting =
        new RuleClass.Builder("string_setting", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .setBuildSetting(new BuildSetting(false, STRING))
            .build();

    assertThat(labelFlag.hasAttr(SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, LABEL)).isTrue();
    assertThat(stringSetting.hasAttr(SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, STRING)).isTrue();
  }

  @Test
  public void testBuildSetting_doesNotCreateDefaultAttributeIfNotBuildSetting() {
    RuleClass stringSetting =
        new RuleClass.Builder("non_build_setting", RuleClassType.NORMAL, false)
            .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
            .add(attr("tags", STRING_LIST))
            .build();

    assertThat(stringSetting.hasAttr(SKYLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, LABEL)).isFalse();
  }
}

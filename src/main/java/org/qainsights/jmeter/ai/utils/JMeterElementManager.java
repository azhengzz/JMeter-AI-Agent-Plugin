package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map;
import java.util.Set;
import javax.swing.tree.TreePath;

/**
 * Utility class for managing JMeter elements (add/delete) in the test plan
 * programmatically.
 */
public class JMeterElementManager {
    private static final Logger log = LoggerFactory.getLogger(JMeterElementManager.class);

    /**
     * JMeter component types for compatibility checking.
     */
    private enum ComponentType {
        TEST_PLAN("TestPlan"),
        THREAD_GROUP("ThreadGroup"),
        CONTROLLER("Controller"),
        FRAGMENT("Fragment"),
        SAMPLER("Sampler"),
        TIMER("Timer"),
        ASSERTION("Assertion"),
        PRE_PROCESSOR("PreProcessor"),
        POST_PROCESSOR("PostProcessor"),
        CONFIG_ELEMENT("ConfigElement"),
        LISTENER("Listener"),
        NON_TEST_ELEMENT("NonTestElement");

        private final String name;

        ComponentType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    /**
     * JMeter compatibility matrix: defines which child component types each parent
     * type can contain.
     */
    private static final Map<ComponentType, Set<ComponentType>> COMPATIBILITY_MATRIX = new HashMap<>();

    static {
        // TestPlan can contain: ThreadGroups, Controllers, Fragments, ConfigElements,
        // Timers, Listeners, Assertions, Pre/Post Processors, Non-Test Elements
        COMPATIBILITY_MATRIX.put(ComponentType.TEST_PLAN, new HashSet<>(Arrays.asList(
                ComponentType.THREAD_GROUP,
                ComponentType.CONTROLLER,
                ComponentType.FRAGMENT,
                ComponentType.CONFIG_ELEMENT,
                ComponentType.TIMER,
                ComponentType.LISTENER,
                ComponentType.ASSERTION,
                ComponentType.PRE_PROCESSOR,
                ComponentType.POST_PROCESSOR,
                ComponentType.NON_TEST_ELEMENT)));

        // ThreadGroup can contain: Samplers, Controllers, Fragments, ConfigElements,
        // Timers, Listeners, Assertions, Pre/Post Processors
        COMPATIBILITY_MATRIX.put(ComponentType.THREAD_GROUP, new HashSet<>(Arrays.asList(
                ComponentType.SAMPLER,
                ComponentType.CONTROLLER,
                ComponentType.FRAGMENT,
                ComponentType.CONFIG_ELEMENT,
                ComponentType.TIMER,
                ComponentType.LISTENER,
                ComponentType.ASSERTION,
                ComponentType.PRE_PROCESSOR,
                ComponentType.POST_PROCESSOR)));

        // Controller can contain: Samplers, Controllers, Fragments, ConfigElements,
        // Timers, Listeners, Assertions, Pre/Post Processors
        COMPATIBILITY_MATRIX.put(ComponentType.CONTROLLER, new HashSet<>(Arrays.asList(
                ComponentType.SAMPLER,
                ComponentType.CONTROLLER,
                ComponentType.FRAGMENT,
                ComponentType.CONFIG_ELEMENT,
                ComponentType.TIMER,
                ComponentType.LISTENER,
                ComponentType.ASSERTION,
                ComponentType.PRE_PROCESSOR,
                ComponentType.POST_PROCESSOR)));

        // Fragment (Test Fragment) can contain: Samplers, Controllers, Fragments,
        // ConfigElements, Timers, Listeners, Assertions, Pre/Post Processors
        COMPATIBILITY_MATRIX.put(ComponentType.FRAGMENT, new HashSet<>(Arrays.asList(
                ComponentType.SAMPLER,
                ComponentType.CONTROLLER,
                ComponentType.FRAGMENT,
                ComponentType.CONFIG_ELEMENT,
                ComponentType.TIMER,
                ComponentType.LISTENER,
                ComponentType.ASSERTION,
                ComponentType.PRE_PROCESSOR,
                ComponentType.POST_PROCESSOR)));

        // Sampler can contain: ConfigElements, Timers, Listeners, Assertions, Pre/Post
        // Processors
        COMPATIBILITY_MATRIX.put(ComponentType.SAMPLER, new HashSet<>(Arrays.asList(
                ComponentType.CONFIG_ELEMENT,
                ComponentType.TIMER,
                ComponentType.LISTENER,
                ComponentType.ASSERTION,
                ComponentType.PRE_PROCESSOR,
                ComponentType.POST_PROCESSOR)));

        // Timers, Assertions, Pre/Post Processors, ConfigElements, Listeners,
        // NonTestElements cannot contain child elements
        COMPATIBILITY_MATRIX.put(ComponentType.TIMER, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.ASSERTION, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.PRE_PROCESSOR, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.POST_PROCESSOR, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.CONFIG_ELEMENT, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.LISTENER, Collections.emptySet());
        COMPATIBILITY_MATRIX.put(ComponentType.NON_TEST_ELEMENT, Collections.emptySet()); // HTTP(S) Test Script
                                                                                          // Recorder
                                                                                          // 组件在Gui上还可以添加组件（暂不处理）
    }

    /**
     * Class to hold model and GUI class names for JMeter elements.
     */
    public static class ElementClassInfo {
        private final String modelClassName;
        private final String guiClassName;

        public ElementClassInfo(String modelClassName, String guiClassName) {
            this.modelClassName = modelClassName;
            this.guiClassName = guiClassName;
        }

        public String getModelClassName() {
            return modelClassName;
        }

        public String getGuiClassName() {
            return guiClassName;
        }
    }

    // Map of common element names to their model and GUI class names
    private static final Map<String, ElementClassInfo> ELEMENT_CLASS_MAP = new HashMap<>();

    static {
        // Samplers
        ELEMENT_CLASS_MAP.put("httpsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));

        ELEMENT_CLASS_MAP.put("httptestsample",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));

        ELEMENT_CLASS_MAP.put("httprequest",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));
        ELEMENT_CLASS_MAP.put("ftprequest", new ElementClassInfo("org.apache.jmeter.protocol.ftp.sampler.FTPSampler",
                "org.apache.jmeter.protocol.ftp.control.gui.FtpTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("jdbcrequest", new ElementClassInfo("org.apache.jmeter.protocol.jdbc.sampler.JDBCSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("javarequest", new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JavaSampler",
                "org.apache.jmeter.protocol.java.control.gui.JavaTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("ldaprequest", new ElementClassInfo("org.apache.jmeter.protocol.ldap.sampler.LDAPSampler",
                "org.apache.jmeter.protocol.ldap.control.gui.LdapTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("ldapeextendedrequest",
                new ElementClassInfo("org.apache.jmeter.protocol.ldap.sampler.LDAPExtSampler",
                        "org.apache.jmeter.protocol.ldap.control.gui.LdapExtTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("accesslogsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.AccessLogSampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("beanshellsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.BeanShellSampler",
                        "org.apache.jmeter.protocol.java.control.gui.BeanShellSamplerGui"));
        ELEMENT_CLASS_MAP.put("jsr223sampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JSR223Sampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("tcpsampler", new ElementClassInfo("org.apache.jmeter.protocol.tcp.sampler.TCPSampler",
                "org.apache.jmeter.protocol.tcp.control.gui.TCPSamplerGui"));
        ELEMENT_CLASS_MAP.put("jmspublisher",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.PublisherSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSPublisherGui"));
        ELEMENT_CLASS_MAP.put("jmssubscriber",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.SubscriberSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSSubscriberGui"));
        ELEMENT_CLASS_MAP.put("jmspointtopoint",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.JMSSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSSamplerGui"));
        ELEMENT_CLASS_MAP.put("junitrequest",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JUnitSampler",
                        "org.apache.jmeter.protocol.java.control.gui.JUnitTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("mailreadersampler",
                new ElementClassInfo("org.apache.jmeter.protocol.mail.sampler.MailReaderSampler",
                        "org.apache.jmeter.protocol.mail.sampler.gui.MailReaderSamplerGui"));
        ELEMENT_CLASS_MAP.put("flowcontrolaction", new ElementClassInfo("org.apache.jmeter.sampler.TestAction",
                "org.apache.jmeter.sampler.gui.TestActionGui"));
        ELEMENT_CLASS_MAP.put("smtpsampler", new ElementClassInfo("org.apache.jmeter.protocol.smtp.sampler.SmtpSampler",
                "org.apache.jmeter.protocol.smtp.sampler.gui.SmtpSamplerGui"));
        ELEMENT_CLASS_MAP.put("osprocesssampler",
                new ElementClassInfo("org.apache.jmeter.protocol.system.SystemSampler",
                        "org.apache.jmeter.protocol.system.gui.SystemSamplerGui"));
        ELEMENT_CLASS_MAP.put("mongodbscript",
                new ElementClassInfo("org.apache.jmeter.protocol.mongodb.sampler.MongoScriptSampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("boltrequest", new ElementClassInfo("org.apache.jmeter.protocol.bolt.sampler.BoltSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("graphqlhttprequest",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.GraphQLHTTPSamplerGui"));
        ELEMENT_CLASS_MAP.put("debugsampler", new ElementClassInfo("org.apache.jmeter.sampler.DebugSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("ajpsampler", new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.AjpSampler",
                "org.apache.jmeter.protocol.http.control.gui.AjpSamplerGui"));

        // Thread Groups
        ELEMENT_CLASS_MAP.put("threadgroup", new ElementClassInfo("org.apache.jmeter.threads.ThreadGroup",
                "org.apache.jmeter.threads.gui.ThreadGroupGui"));

        // Assertions
        ELEMENT_CLASS_MAP.put("responseassert", new ElementClassInfo("org.apache.jmeter.assertions.ResponseAssertion",
                "org.apache.jmeter.assertions.gui.AssertionGui"));
        ELEMENT_CLASS_MAP.put("jsonassertion", new ElementClassInfo("org.apache.jmeter.assertions.JSONPathAssertion",
                "org.apache.jmeter.assertions.gui.JSONPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("durationassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.DurationAssertion",
                        "org.apache.jmeter.assertions.gui.DurationAssertionGui"));
        ELEMENT_CLASS_MAP.put("sizeassertion", new ElementClassInfo("org.apache.jmeter.assertions.SizeAssertion",
                "org.apache.jmeter.assertions.gui.SizeAssertionGui"));
        ELEMENT_CLASS_MAP.put("xpathassertion", new ElementClassInfo("org.apache.jmeter.assertions.XPathAssertion",
                "org.apache.jmeter.assertions.gui.XPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("jsr223assertion", new ElementClassInfo("org.apache.jmeter.assertions.JSR223Assertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("xpath2assertion", new ElementClassInfo("org.apache.jmeter.assertions.XPath2Assertion",
                "org.apache.jmeter.assertions.gui.XPath2AssertionGui"));
        ELEMENT_CLASS_MAP.put("compareassert", new ElementClassInfo("org.apache.jmeter.assertions.CompareAssertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("htmlassertion", new ElementClassInfo("org.apache.jmeter.assertions.HTMLAssertion",
                "org.apache.jmeter.assertions.gui.HTMLAssertionGui"));
        ELEMENT_CLASS_MAP.put("jmespathassert",
                new ElementClassInfo("org.apache.jmeter.assertions.jmespath.JMESPathAssertion",
                        "org.apache.jmeter.assertions.jmespath.gui.JMESPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("md5assertion", new ElementClassInfo("org.apache.jmeter.assertions.MD5HexAssertion",
                "org.apache.jmeter.assertions.gui.MD5HexAssertionGUI"));
        ELEMENT_CLASS_MAP.put("smimeassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.SMIMEAssertionTestElement",
                        "org.apache.jmeter.assertions.gui.SMIMEAssertionGui"));
        ELEMENT_CLASS_MAP.put("xmlassertion", new ElementClassInfo("org.apache.jmeter.assertions.XMLAssertion",
                "org.apache.jmeter.assertions.gui.XMLAssertionGui"));
        ELEMENT_CLASS_MAP.put("xmlschemaassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.XMLSchemaAssertion",
                        "org.apache.jmeter.assertions.gui.XMLSchemaAssertionGUI"));
        ELEMENT_CLASS_MAP.put("beanshellassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.BeanShellAssertion",
                        "org.apache.jmeter.assertions.gui.BeanShellAssertionGui"));

        // Timers
        ELEMENT_CLASS_MAP.put("constanttimer", new ElementClassInfo("org.apache.jmeter.timers.ConstantTimer",
                "org.apache.jmeter.timers.gui.ConstantTimerGui"));
        ELEMENT_CLASS_MAP.put("uniformrandomtimer", new ElementClassInfo("org.apache.jmeter.timers.UniformRandomTimer",
                "org.apache.jmeter.timers.gui.UniformRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("gaussianrandomtimer", new ElementClassInfo(
                "org.apache.jmeter.timers.GaussianRandomTimer", "org.apache.jmeter.timers.gui.GaussianRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("poissonrandomtimer", new ElementClassInfo("org.apache.jmeter.timers.PoissonRandomTimer",
                "org.apache.jmeter.timers.gui.PoissonRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("precisethroughputtimer",
                new ElementClassInfo("org.apache.jmeter.timers.poissonarrivals.PreciseThroughputTimer",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("constantthroughputtimer", new ElementClassInfo(
                "org.apache.jmeter.timers.ConstantThroughputTimer", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223timer", new ElementClassInfo("org.apache.jmeter.timers.JSR223Timer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("synctimer", new ElementClassInfo("org.apache.jmeter.timers.SyncTimer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("beanshelltimer", new ElementClassInfo("org.apache.jmeter.timers.BeanShellTimer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Pre Processors
        ELEMENT_CLASS_MAP.put("jsr223preprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.JSR223PreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("userparameters", new ElementClassInfo("org.apache.jmeter.modifiers.UserParameters",
                "org.apache.jmeter.modifiers.gui.UserParametersGui"));
        ELEMENT_CLASS_MAP.put("anchormodifier",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.AnchorModifier",
                        "org.apache.jmeter.protocol.http.modifier.gui.AnchorModifierGui"));
        ELEMENT_CLASS_MAP.put("urlrewritingmodifier",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.URLRewritingModifier",
                        "org.apache.jmeter.protocol.http.modifier.gui.URLRewritingModifierGui"));
        ELEMENT_CLASS_MAP.put("jdbcpreprocessor",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.processor.JDBCPreProcessor",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("regexuserparameters",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.RegExUserParameters",
                        "org.apache.jmeter.protocol.http.modifier.gui.RegExUserParametersGui"));
        ELEMENT_CLASS_MAP.put("sampletimeout", new ElementClassInfo("org.apache.jmeter.modifiers.SampleTimeout",
                "org.apache.jmeter.modifiers.gui.SampleTimeoutGui"));
        ELEMENT_CLASS_MAP.put("beanshellpreprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.BeanShellPreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Post Processors / Extractors
        ELEMENT_CLASS_MAP.put("regexextractor", new ElementClassInfo("org.apache.jmeter.extractor.RegexExtractor",
                "org.apache.jmeter.extractor.gui.RegexExtractorGui"));
        ELEMENT_CLASS_MAP.put("xpathextractor", new ElementClassInfo("org.apache.jmeter.extractor.XPathExtractor",
                "org.apache.jmeter.extractor.gui.XPathExtractorGui"));
        ELEMENT_CLASS_MAP.put("jsonpostprocessor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor",
                        "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui"));
        ELEMENT_CLASS_MAP.put("jsonpathextractor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor",
                        "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui"));
        ELEMENT_CLASS_MAP.put("boundaryextractor", new ElementClassInfo("org.apache.jmeter.extractor.BoundaryExtractor",
                "org.apache.jmeter.extractor.gui.BoundaryExtractorGui"));
        ELEMENT_CLASS_MAP.put("htmlextractor", new ElementClassInfo("org.apache.jmeter.extractor.HtmlExtractor",
                "org.apache.jmeter.extractor.gui.HtmlExtractorGui"));
        ELEMENT_CLASS_MAP.put("jmespathextractor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor",
                        "org.apache.jmeter.extractor.json.jmespath.gui.JMESPathExtractorGui"));
        ELEMENT_CLASS_MAP.put("jsr223postprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.JSR223PostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("debugpostprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.DebugPostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jdbcpostprocessor",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.processor.JDBCPostProcessor",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("resultaction", new ElementClassInfo("org.apache.jmeter.reporters.ResultAction",
                "org.apache.jmeter.reporters.gui.ResultActionGui"));
        ELEMENT_CLASS_MAP.put("xpath2extractor", new ElementClassInfo("org.apache.jmeter.extractor.XPath2Extractor",
                "org.apache.jmeter.extractor.gui.XPath2ExtractorGui"));
        ELEMENT_CLASS_MAP.put("beanshellpostprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.BeanShellPostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Config Elements
        ELEMENT_CLASS_MAP.put("csvdatasetconfig", new ElementClassInfo("org.apache.jmeter.config.CSVDataSet",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("csvdataset", new ElementClassInfo("org.apache.jmeter.config.CSVDataSet",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("headermanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.HeaderManager",
                        "org.apache.jmeter.protocol.http.gui.HeaderPanel"));
        ELEMENT_CLASS_MAP.put("cookiemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.CookieManager",
                        "org.apache.jmeter.protocol.http.gui.CookiePanel"));
        ELEMENT_CLASS_MAP.put("cachemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.CacheManager",
                        "org.apache.jmeter.protocol.http.gui.CacheManagerGui"));
        ELEMENT_CLASS_MAP.put("httpdefaults", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui"));
        ELEMENT_CLASS_MAP.put("boltconnection",
                new ElementClassInfo("org.apache.jmeter.protocol.bolt.config.BoltConnectionElement",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("counterconfig", new ElementClassInfo("org.apache.jmeter.modifiers.CounterConfig",
                "org.apache.jmeter.modifiers.gui.CounterConfigGui"));
        ELEMENT_CLASS_MAP.put("dnscachemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.DNSCacheManager",
                        "org.apache.jmeter.protocol.http.gui.DNSCachePanel"));
        ELEMENT_CLASS_MAP.put("ftpconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ftp.config.gui.FtpConfigGui"));
        ELEMENT_CLASS_MAP.put("authmanager", new ElementClassInfo("org.apache.jmeter.protocol.http.control.AuthManager",
                "org.apache.jmeter.protocol.http.gui.AuthPanel"));
        ELEMENT_CLASS_MAP.put("jdbcdatasource",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.config.DataSourceElement",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("javaconfig", new ElementClassInfo("org.apache.jmeter.protocol.java.config.JavaConfig",
                "org.apache.jmeter.protocol.java.config.gui.JavaConfigGui"));
        ELEMENT_CLASS_MAP.put("keystoreconfig", new ElementClassInfo("org.apache.jmeter.config.KeystoreConfig",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("ldapextconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ldap.config.gui.LdapExtConfigGui"));
        ELEMENT_CLASS_MAP.put("ldapconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ldap.config.gui.LdapConfigGui"));
        ELEMENT_CLASS_MAP.put("loginconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.config.gui.LoginConfigGui"));
        ELEMENT_CLASS_MAP.put("randomvariable", new ElementClassInfo("org.apache.jmeter.config.RandomVariableConfig",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("simpleconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.config.gui.SimpleConfigGui"));
        ELEMENT_CLASS_MAP.put("tcpconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.tcp.config.gui.TCPConfigGui"));
        ELEMENT_CLASS_MAP.put("arguments", new ElementClassInfo("org.apache.jmeter.config.Arguments",
                "org.apache.jmeter.config.gui.ArgumentsPanel"));
        ELEMENT_CLASS_MAP.put("ftprequestdefaults", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ftp.config.gui.FtpConfigGui"));
        ELEMENT_CLASS_MAP.put("httpauthorizationmanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.AuthManager",
                        "org.apache.jmeter.protocol.http.gui.AuthPanel"));

        // Listeners
        ELEMENT_CLASS_MAP.put("viewresultstree", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer"));
        ELEMENT_CLASS_MAP.put("summaryreport", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.SummaryReport"));
        ELEMENT_CLASS_MAP.put("aggregatereport", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatVisualizer"));
        ELEMENT_CLASS_MAP.put("backendlistener",
                new ElementClassInfo("org.apache.jmeter.visualizers.backend.BackendListener",
                        "org.apache.jmeter.visualizers.backend.BackendListenerGui"));
        ELEMENT_CLASS_MAP.put("statgraphvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatGraphVisualizer"));
        ELEMENT_CLASS_MAP.put("assertionvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.AssertionVisualizer"));
        ELEMENT_CLASS_MAP.put("comparisonvisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.ResultCollector", "org.apache.jmeter.visualizers.ComparisonVisualizer"));
        ELEMENT_CLASS_MAP.put("summariser", new ElementClassInfo("org.apache.jmeter.reporters.Summariser",
                "org.apache.jmeter.reporters.gui.SummariserGui"));
        ELEMENT_CLASS_MAP.put("graphvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.GraphVisualizer"));
        ELEMENT_CLASS_MAP.put("jsr223listener", new ElementClassInfo("org.apache.jmeter.visualizers.JSR223Listener",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("mailervisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.MailerResultCollector", "org.apache.jmeter.visualizers.MailerVisualizer"));
        ELEMENT_CLASS_MAP.put("resptimegraph", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.RespTimeGraphVisualizer"));
        ELEMENT_CLASS_MAP.put("resultsaver", new ElementClassInfo("org.apache.jmeter.reporters.ResultSaver",
                "org.apache.jmeter.reporters.gui.ResultSaverGui"));
        ELEMENT_CLASS_MAP.put("simpledatawriter", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.SimpleDataWriter"));
        ELEMENT_CLASS_MAP.put("tablevisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.TableVisualizer"));
        ELEMENT_CLASS_MAP.put("beanshelllistener", new ElementClassInfo(
                "org.apache.jmeter.visualizers.BeanShellListener", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // JSR223 Elements
        ELEMENT_CLASS_MAP.put("jsr223sampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JSR223Sampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223preprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.JSR223PreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223postprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.JSR223PostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223listener", new ElementClassInfo("org.apache.jmeter.visualizers.JSR223Listener",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223assertion", new ElementClassInfo("org.apache.jmeter.assertions.JSR223Assertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223timer", new ElementClassInfo("org.apache.jmeter.timers.JSR223Timer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Controllers
        ELEMENT_CLASS_MAP.put("loopcontroller", new ElementClassInfo("org.apache.jmeter.control.LoopController",
                "org.apache.jmeter.control.gui.LoopControlPanel"));
        ELEMENT_CLASS_MAP.put("ifcontroller", new ElementClassInfo("org.apache.jmeter.control.IfController",
                "org.apache.jmeter.control.gui.IfControllerPanel"));
        ELEMENT_CLASS_MAP.put("whilecontroller", new ElementClassInfo("org.apache.jmeter.control.WhileController",
                "org.apache.jmeter.control.gui.WhileControllerGui"));
        ELEMENT_CLASS_MAP.put("transactioncontroller",
                new ElementClassInfo("org.apache.jmeter.control.TransactionController",
                        "org.apache.jmeter.control.gui.TransactionControllerGui"));
        ELEMENT_CLASS_MAP.put("simplecontroller", new ElementClassInfo("org.apache.jmeter.control.GenericController",
                "org.apache.jmeter.control.gui.LogicControllerPanel"));
        ELEMENT_CLASS_MAP.put("onceonlycontroller", new ElementClassInfo("org.apache.jmeter.control.OnceOnlyController",
                "org.apache.jmeter.control.gui.OnceOnlyControllerPanel"));
        ELEMENT_CLASS_MAP.put("interleavecontroller", new ElementClassInfo(
                "org.apache.jmeter.control.InterleaveControl", "org.apache.jmeter.control.gui.InterleaveControlPanel"));
        ELEMENT_CLASS_MAP.put("randomcontroller", new ElementClassInfo("org.apache.jmeter.control.RandomController",
                "org.apache.jmeter.control.gui.RandomControlPanel"));
        ELEMENT_CLASS_MAP.put("randomordercontroller",
                new ElementClassInfo("org.apache.jmeter.control.RandomOrderController",
                        "org.apache.jmeter.control.gui.RandomOrderControllerPanel"));
        ELEMENT_CLASS_MAP.put("throughputcontroller",
                new ElementClassInfo("org.apache.jmeter.control.ThroughputController",
                        "org.apache.jmeter.control.gui.ThroughputControllerPanel"));
        ELEMENT_CLASS_MAP.put("runtimecontroller",
                new ElementClassInfo("org.apache.jmeter.control.RunTime", "org.apache.jmeter.control.gui.RunTimeGui"));
        ELEMENT_CLASS_MAP.put("switchcontroller", new ElementClassInfo("org.apache.jmeter.control.SwitchController",
                "org.apache.jmeter.control.gui.SwitchControllerPanel"));
        ELEMENT_CLASS_MAP.put("foreachcontroller", new ElementClassInfo("org.apache.jmeter.control.ForeachController",
                "org.apache.jmeter.control.gui.ForeachControlPanel"));
        ELEMENT_CLASS_MAP.put("modulecontroller", new ElementClassInfo("org.apache.jmeter.control.ModuleController",
                "org.apache.jmeter.control.gui.ModuleControllerGui"));
        ELEMENT_CLASS_MAP.put("includecontroller", new ElementClassInfo("org.apache.jmeter.control.IncludeController",
                "org.apache.jmeter.control.gui.IncludeControllerPanel"));
        ELEMENT_CLASS_MAP.put("recordingcontroller", new ElementClassInfo(
                "org.apache.jmeter.control.RecordingController", "org.apache.jmeter.control.gui.RecordController"));
        ELEMENT_CLASS_MAP.put("criticalsectioncontroller",
                new ElementClassInfo("org.apache.jmeter.control.CriticalSectionController",
                        "org.apache.jmeter.control.gui.CriticalSectionControllerPanel"));

        // Additional Listeners
        ELEMENT_CLASS_MAP.put("sampleresultsaveconfiguration",
                new ElementClassInfo("org.apache.jmeter.samplers.SampleSaveConfiguration",
                        "org.apache.jmeter.samplers.SampleSaveConfiguration"));
        ELEMENT_CLASS_MAP.put("graphresults", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.GraphVisualizer"));
        ELEMENT_CLASS_MAP.put("assertionresults", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.AssertionVisualizer"));
        ELEMENT_CLASS_MAP.put("viewresultsintable", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.TableVisualizer"));
        ELEMENT_CLASS_MAP.put("saveresponsestoafile", new ElementClassInfo("org.apache.jmeter.reporters.ResultSaver",
                "org.apache.jmeter.reporters.gui.ResultSaverGui"));
        ELEMENT_CLASS_MAP.put("generatesummaryresults", new ElementClassInfo("org.apache.jmeter.reporters.Summariser",
                "org.apache.jmeter.reporters.gui.SummariserGui"));
        ELEMENT_CLASS_MAP.put("comparisonassertionvisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.ResultCollector", "org.apache.jmeter.visualizers.ComparisonVisualizer"));
        ELEMENT_CLASS_MAP.put("aggregategraph", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatGraphVisualizer"));
    }

    /**
     * A generic method to create a test element using its class and GUI class
     *
     * @param elementClass The class of the test element
     * @param guiClass     The GUI class of the test element
     * @return The created test element
     */
    public static <T extends TestElement> T createTestElement(Class<T> elementClass,
            Class<? extends JMeterGUIComponent> guiClass) {
        try {
            log.info("Creating test element of class: {} with GUI class: {}", elementClass.getName(),
                    guiClass.getName());

            // Create the element instance
            T element = elementClass.getDeclaredConstructor().newInstance();
            if (element == null) {
                log.error("Failed to instantiate element of class: {}", elementClass.getName());
                throw new RuntimeException("Failed to instantiate element");
            }
            // Set the required properties
            element.setProperty(TestElement.TEST_CLASS, elementClass.getName());
            element.setProperty(TestElement.GUI_CLASS, guiClass.getName());

            // For thread groups, ensure they have a proper name to avoid NPE
            if (element.getClass().getSimpleName().contains("ThreadGroup")) {
                element.setName("Thread Group");
            }

            log.info("Successfully created element: {}", element.getClass().getSimpleName());
            return element;
        } catch (Exception e) {
            log.error("Failed to create test element of class: {} with GUI class: {}",
                    elementClass.getName(), guiClass.getName(), e);
            throw new RuntimeException("Failed to create test element: " + e.getMessage(), e);
        }
    }

    /**
     * Create a new test element of the specified type.
     *
     * @param elementType The normalized element type
     * @param elementName The name for the element
     * @return The created test element, or null if creation failed
     */
    public static TestElement createElement(String elementType, String elementName) {
        log.info("Creating element of type: {} with name: {}", elementType, elementName);

        // Get the class info for the element type
        ElementClassInfo classInfo = getElementClassInfo(elementType);
        if (classInfo == null) {
            log.error("Could not find class info for element type: {}", elementType);
            return null;
        }

        try {
            Class<?> elementClass = Class.forName(classInfo.getModelClassName());
            Class<? extends JMeterGUIComponent> guiClass =
                    Class.forName(classInfo.getGuiClassName()).asSubclass(JMeterGUIComponent.class);

            TestElement element = createTestElement(elementClass.asSubclass(TestElement.class), guiClass);

            // Set a name for the element
            if (elementName != null && !elementName.isEmpty()) {
                element.setName(elementName);
            }

            log.info("Successfully created element: {}", element.getClass().getSimpleName());
            return element;

        } catch (Exception e) {
            log.error("Failed to create test element of type: {}", elementType, e);
            return null;
        }
    }

    /**
     * Adds a JMeter element to the currently selected node in the test plan.
     *
     * @param elementType The type of element to add (case-insensitive, spaces
     *                    ignored)
     * @param elementName The name to give the new element (optional, will use
     *                    default if null)
     * @return true if the element was added successfully, false otherwise
     */
    public static boolean addElement(String elementType, String elementName) {
        try {
            log.info("Adding element of type: {} with name: {}", elementType, elementName);

            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot add element");
                return false;
            }

            // Get the currently selected node
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.error("No node is currently selected in the test plan");
                return false;
            }
            log.info("Current node: {}", currentNode.getName());

            // Normalize the element type for lookup
            String normalizedType = normalizeElementType(elementType);
            log.info("Normalized element type: {}", normalizedType);

            // Get the class info for the element type
            ElementClassInfo classInfo = ELEMENT_CLASS_MAP.get(normalizedType);
            if (classInfo == null) {
                log.error("Unknown element type: {}", elementType);
                return false;
            }
            log.info("Attempting to create element of type: {} with model class: {} and GUI class: {}", normalizedType,
                    classInfo.modelClassName, classInfo.guiClassName);

            try {
                Class<?> elementClass = Class.forName(classInfo.modelClassName);
                Class<? extends JMeterGUIComponent> guiClass = Class.forName(classInfo.guiClassName)
                        .asSubclass(JMeterGUIComponent.class);
                TestElement newElement = createTestElement(elementClass.asSubclass(TestElement.class), guiClass);

                // Special handling for Thread Group to ensure it has a controller
                // This is necessary because JMeter requires a loop controller for Thread Groups
                if (normalizedType.equals("threadgroup")) {
                    log.info("Initializing Thread Group with a Loop Controller");
                    org.apache.jmeter.threads.ThreadGroup threadGroup = (org.apache.jmeter.threads.ThreadGroup) newElement;

                    // Create and initialize a LoopController
                    org.apache.jmeter.control.LoopController loopController = new org.apache.jmeter.control.LoopController();
                    loopController.setLoops(1);
                    loopController.setFirst(true);
                    loopController.setProperty(TestElement.TEST_CLASS,
                            org.apache.jmeter.control.LoopController.class.getName());
                    loopController.setProperty(TestElement.GUI_CLASS,
                            org.apache.jmeter.control.gui.LoopControlPanel.class.getName());

                    // Set the controller on the Thread Group
                    threadGroup.setSamplerController(loopController);
                    log.info("Loop Controller initialized for Thread Group");
                }

                // Set a name for the element
                if (elementName != null && !elementName.isEmpty()) {
                    newElement.setName(elementName);
                } else {
                    // Use a default name based on the element type
                    newElement.setName(getDefaultNameForElement(normalizedType));
                }

                log.info("Adding element to node: {}", currentNode.getName());

                // Check if the current node is compatible with the element type
                if (!isNodeCompatible(currentNode, elementType)) {
                    log.error("Current node is not compatible with element type: {}", elementType);
                    return false;
                }

                log.info("Adding element to node: {}", currentNode.getName());

                // Add the element to the test plan
                guiPackage.getTreeModel().addComponent(newElement, currentNode);
                log.info("Successfully added element to the tree model");

                // Refresh the tree to show the new element
                guiPackage.getTreeModel().nodeStructureChanged(currentNode);
                log.info("Successfully refreshed the tree");

                return true;
            } catch (Exception e) {
                log.error("Failed to create or add element", e);
                return false;
            }
        } catch (Exception e) {
            log.error("Error adding element to the test plan", e);
            return false;
        }
    }

    /**
     * Checks if the test plan is ready for operations.
     * 
     * @return A TestPlanStatus object indicating if the test plan is ready and any
     *         error message
     */
    public static TestPlanStatus isTestPlanReady() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return new TestPlanStatus(false, "JMeter GUI is not available");
        }

        // Check if a test plan is open
        if (guiPackage.getTreeModel() == null || guiPackage.getTreeModel().getRoot() == null) {
            return new TestPlanStatus(false, "No test plan is currently open");
        }

        return new TestPlanStatus(true, null);
    }

    /**
     * Ensures that a test plan exists, creating one if necessary.
     * 
     * @return true if a test plan exists or was created successfully, false
     *         otherwise
     */
    public static boolean ensureTestPlanExists() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot ensure test plan exists");
            return false;
        }

        // Check if a test plan is already open
        if (guiPackage.getTreeModel() != null && guiPackage.getTreeModel().getRoot() != null) {
            log.info("Test plan already exists");
            return true;
        }

        try {
            // Create a new test plan directly
            org.apache.jmeter.testelement.TestPlan testPlan = new org.apache.jmeter.testelement.TestPlan();
            testPlan.setName("Test Plan");
            testPlan.setProperty(TestElement.TEST_CLASS, org.apache.jmeter.testelement.TestPlan.class.getName());
            testPlan.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.control.gui.TestPlanGui.class.getName());

            // Create a root node with the test plan
            JMeterTreeNode root = new JMeterTreeNode(testPlan, null);

            // Add the root node to the tree model
            guiPackage.getTreeModel().setRoot(root);

            log.info("Created a new test plan");
            return true;
        } catch (Exception e) {
            log.error("Error creating a new test plan", e);
            return false;
        }
    }

    /**
     * Selects the test plan node in the tree.
     * 
     * @return true if the test plan node was selected successfully, false otherwise
     */
    public static boolean selectTestPlanNode() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot select test plan node");
            return false;
        }

        try {
            // Get the root node (test plan)
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (root == null) {
                log.error("Root node is null, cannot select test plan node");
                return false;
            }

            // Select the test plan node
            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(root.getPath()));
            log.info("Selected the test plan node");
            return true;
        } catch (Exception e) {
            log.error("Error selecting the test plan node", e);
            return false;
        }
    }

    /**
     * Normalizes the element type by removing spaces and converting to lowercase.
     * 
     * @param elementType The element type to normalize
     * @return The normalized element type
     */
    public static String normalizeElementType(String elementType) {
        if (elementType == null) {
            return "";
        }
        return elementType.toLowerCase().replaceAll("[\\s-]+", "");
    }

    /**
     * Gets a default name for an element based on its type.
     * 
     * @param elementType The normalized element type
     * @return A default name for the element
     */
    public static String getDefaultNameForElement(String elementType) {
        if (elementType == null) {
            return "New Element";
        }

        String normalizedType = normalizeElementType(elementType);

        switch (normalizedType) {
            case "httptestsample":
                return "HTTP Request";
            case "httpsampler":
                return "HTTP Request";
            case "loopcontroller":
                return "Loop Controller";
            case "ifcontroller":
                return "If Controller";
            case "whilecontroller":
                return "While Controller";
            case "transactioncontroller":
                return "Transaction Controller";
            case "runtimecontroller":
                return "Runtime Controller";
            case "headermanager":
                return "HTTP Header Manager";
            case "csvdataset":
                return "CSV Data Set";
            case "threadgroup":
                return "Thread Group";
            case "responseassert":
                return "Response Assertion";
            case "jsonassertion":
                return "JSON Path Assertion";
            case "durationassertion":
                return "Duration Assertion";
            case "sizeassertion":
                return "Size Assertion";
            case "xpathassertion":
                return "XPath Assertion";
            case "constanttimer":
                return "Constant Timer";
            case "uniformrandomtimer":
                return "Uniform Random Timer";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer";
            case "poissonrandomtimer":
                return "Poisson Random Timer";
            case "regexextractor":
                return "Regular Expression Extractor";
            case "xpathextractor":
                return "XPath Extractor";
            case "jsonpathextractor":
                return "JSON Path Extractor";
            case "boundaryextractor":
                return "Boundary Extractor";
            case "viewresultstree":
                return "View Results Tree";
            case "aggregatereport":
                return "Aggregate Report";
            case "jsr223sampler":
                return "JSR223 Sampler";
            case "jsr223preprocessor":
                return "JSR223 PreProcessor";
            case "jsr223postprocessor":
                return "JSR223 PostProcessor";
            default:
                // Convert camelCase to Title Case with spaces
                String name = normalizedType.replaceAll("([a-z])([A-Z])", "$1 $2");
                name = name.substring(0, 1).toUpperCase() + name.substring(1);

                return name;
        }
    }

    /**
     * Gets the map of element types to class names.
     * 
     * @return The element class map
     */
    public static Map<String, ElementClassInfo> getElementClassMap() {
        return ELEMENT_CLASS_MAP;
    }

    /**
     * Checks if the given element type is supported.
     *
     * @param elementType The element type to check
     * @return true if the element type is supported, false otherwise
     */
    public static boolean isElementTypeSupported(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        return ELEMENT_CLASS_MAP.containsKey(normalizedType);
    }

    /**
     * Get the class info for an element type.
     *
     * @param elementType The normalized element type
     * @return The ElementClassInfo, or null if not found
     */
    public static ElementClassInfo getElementClassInfo(String elementType) {
        return ELEMENT_CLASS_MAP.get(elementType);
    }

    /**
     * Gets a list of all supported element types.
     *
     * @return A string containing all supported element types
     */
    public static String getSupportedElementTypes() {
        StringBuilder sb = new StringBuilder();

        // Group elements by category
        Map<String, StringBuilder> categories = new HashMap<>();
        categories.put("Samplers", new StringBuilder());
        categories.put("Controllers", new StringBuilder());
        categories.put("Config Elements", new StringBuilder());
        categories.put("Thread Groups", new StringBuilder());
        categories.put("Assertions", new StringBuilder());
        categories.put("Timers", new StringBuilder());
        categories.put("Extractors", new StringBuilder());
        categories.put("Listeners", new StringBuilder());
        categories.put("JSR223 Elements", new StringBuilder());

        // Add elements to their respective categories
        for (Map.Entry<String, ElementClassInfo> entry : ELEMENT_CLASS_MAP.entrySet()) {
            String className = entry.getValue().guiClassName;
            if (className.contains("sampler")) {
                categories.get("Samplers").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("control")) {
                categories.get("Controllers").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("config") || className.contains("manager")) {
                categories.get("Config Elements").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("threads")) {
                categories.get("Thread Groups").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("assertions")) {
                categories.get("Assertions").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("timers")) {
                categories.get("Timers").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("extractor")) {
                categories.get("Extractors").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("visualizers") || className.contains("report")) {
                categories.get("Listeners").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("JSR223")) {
                categories.get("JSR223 Elements").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            }
        }

        // Build the final string
        for (String category : categories.keySet()) {
            if (categories.get(category).length() > 0) {
                sb.append(category).append(":\n");
                sb.append(categories.get(category));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Gets the JMeter GUI class for an element type.
     * 
     * @param elementType The element type
     * @return The JMeter GUI class for the element type, or null if not found
     */
    public static Class<?> getJMeterGuiClass(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        ElementClassInfo classInfo = ELEMENT_CLASS_MAP.get(normalizedType);

        if (classInfo == null) {
            return null;
        }

        try {
            return Class.forName(classInfo.guiClassName);
        } catch (ClassNotFoundException e) {
            log.error("Class not found: {}", classInfo.guiClassName, e);
            return null;
        }
    }

    /**
     * Infers the component type from a TestElement using inheritance/interface
     * checks.
     * This is more accurate than string-based inference.
     *
     * @param element the TestElement to analyze
     * @return the inferred ComponentType, or null if unable to determine
     */
    private static ComponentType inferComponentType(TestElement element) {
        if (element == null) {
            return null;
        }

        Class<?> clazz = element.getClass();

        // Check using JMeter interfaces and inheritance
        // Check in order of specificity (more specific types first)

        // TestPlan - specific class check
        if (TestPlan.class.isAssignableFrom(clazz)) {
            return ComponentType.TEST_PLAN;
        }

        // ThreadGroup - extends AbstractThreadGroup
        if (AbstractThreadGroup.class.isAssignableFrom(clazz)) {
            return ComponentType.THREAD_GROUP;
        }

        // Sampler - implements Sampler interface
        if (Sampler.class.isAssignableFrom(clazz)) {
            return ComponentType.SAMPLER;
        }

        // Test Fragment - specific class (extends Controller, so **check before generic
        // Controller**)
        if (TestFragmentController.class.isAssignableFrom(clazz)) {
            return ComponentType.FRAGMENT;
        }

        // Controller - implements Controller interface
        if (Controller.class.isAssignableFrom(clazz)) {
            return ComponentType.CONTROLLER;
        }

        // Timer - implements Timer interface
        if (Timer.class.isAssignableFrom(clazz)) {
            return ComponentType.TIMER;
        }

        // Assertion - implements Assertion interface
        if (Assertion.class.isAssignableFrom(clazz)) {
            return ComponentType.ASSERTION;
        }

        // PreProcessor - implements PreProcessor interface
        if (PreProcessor.class.isAssignableFrom(clazz)) {
            return ComponentType.PRE_PROCESSOR;
        }

        // PostProcessor - implements PostProcessor interface
        if (PostProcessor.class.isAssignableFrom(clazz)) {
            return ComponentType.POST_PROCESSOR;
        }

        // ConfigElement - implements ConfigElement interface
        if (ConfigElement.class.isAssignableFrom(clazz)) {
            return ComponentType.CONFIG_ELEMENT;
        }

        // Non-Test Elements - check specific classes (exact match for accuracy)
        String simpleName = clazz.getSimpleName();
        if ("ProxyControl".equals(simpleName) || "HttpMirrorServer".equals(simpleName)) {
            return ComponentType.NON_TEST_ELEMENT;
        }
        // TODO Property Display 没有匹配上

        // Listener - no common interface, use class name patterns
        if ("ResultCollector".equals(simpleName) || "BackendListener".equals(simpleName)
                || "Summariser".equals(simpleName)
                || "JSR223Listener".equals(simpleName) || "MailerResultCollector".equals(simpleName)
                || "ResultSaver".equals(simpleName)
                || "BeanShellListener".equals(simpleName)) {
            return ComponentType.LISTENER;
        }

        // Fallback to string-based inference
        return inferComponentType(simpleName);
    }

    /**
     * Infers the component type from a class name or GUI class name.
     *
     * @param className the class name to analyze
     * @return the inferred ComponentType, or null if unable to determine
     */
    private static ComponentType inferComponentType(String className) {
        if (className == null) {
            return null;
        }

        // Check for exact matches first
        if (className.contains("TestPlan")) {
            return ComponentType.TEST_PLAN;
        }
        if (className.contains("ThreadGroup")) {
            return ComponentType.THREAD_GROUP;
        }
        // Check for Test Fragment before generic Controller (since
        // TestFragmentController also contains "Controller")
        if (className.contains("Fragment") || className.contains("TestFragment")) {
            return ComponentType.FRAGMENT;
        }
        if (className.contains("Sampler") || className.contains("Request")) {
            return ComponentType.SAMPLER;
        }
        if (className.contains("Controller") && !className.contains("ControllerGui")) {
            return ComponentType.CONTROLLER;
        }
        if (className.contains("Timer")) {
            return ComponentType.TIMER;
        }
        if (className.contains("Assertion")) {
            return ComponentType.ASSERTION;
        }
        if (className.contains("PreProcessor") || className.contains("PreProcessor")) {
            return ComponentType.PRE_PROCESSOR;
        }
        if (className.contains("PostProcessor") || className.contains("Extractor")) {
            return ComponentType.POST_PROCESSOR;
        }

        // Check for Config elements
        if (className.contains("Config") || className.contains("Manager") ||
                className.contains("DataSet") || className.contains("Cache")) {
            return ComponentType.CONFIG_ELEMENT;
        }

        // Check for Listeners/Visualizers
        if (className.contains("Listener") || className.contains("Visualizer") ||
                className.contains("Report") || className.contains("Tree") ||
                className.contains("Table") || className.contains("Graph")) {
            return ComponentType.LISTENER;
        }

        // Check GUI classes
        // Check for Fragment GUI before generic Controller GUI
        if (className.contains("FragmentGui") || className.contains("TestFragment")) {
            return ComponentType.FRAGMENT;
        }
        if (className.contains("ControllerGui") || className.contains("ControllerPanel")) {
            return ComponentType.CONTROLLER;
        }
        if (className.contains("SamplerGui")) {
            return ComponentType.SAMPLER;
        }
        if (className.contains("TimerGui")) {
            return ComponentType.TIMER;
        }
        if (className.contains("AssertionGui")) {
            return ComponentType.ASSERTION;
        }
        if (className.contains("PreProcessorGui")) {
            return ComponentType.PRE_PROCESSOR;
        }
        if (className.contains("PostProcessorGui")) {
            return ComponentType.POST_PROCESSOR;
        }
        if (className.contains("ConfigGui")) {
            return ComponentType.CONFIG_ELEMENT;
        }
        if (className.contains("ListenerGui")) {
            return ComponentType.LISTENER;
        }

        // Check for Non-Test Elements (Proxy, Mirror Server, etc.)
        if (className.contains("ProxyControl") || className.contains("ProxyGui") ||
                className.contains("MirrorServer") || className.contains("MirrorGui") ||
                className.contains("PropertyDisplay") || className.contains("PropertyDisplayGui")) {
            return ComponentType.NON_TEST_ELEMENT;
        }

        return null;
    }

    /**
     * Checks if a child component can be added to a parent component using
     * ComponentType comparison.
     *
     * @param parentType the parent component type
     * @param childType  the child component type
     * @return true if compatible, false otherwise
     */
    private static boolean isCompatibleUsingComponentTypes(ComponentType parentType, ComponentType childType) {
        log.info("--- Checking component types compatibility ---");
        log.info("Parent type: {}", parentType);
        log.info("Child type: {}", childType);

        Set<ComponentType> supportedChildTypes = COMPATIBILITY_MATRIX.get(parentType);
        if (supportedChildTypes == null || supportedChildTypes.isEmpty()) {
            log.info("Parent type {} cannot contain any child elements", parentType);
            return false;
        }
        log.info("Parent supported child types: {}", supportedChildTypes);

        // Check if child type is supported by parent
        boolean compatible = supportedChildTypes.contains(childType);
        log.info("Compatibility result: {}", compatible ? "COMPATIBLE" : "INCOMPATIBLE");
        return compatible;
    }

    /**
     * Checks if a node is compatible with the given element based on JMeter's
     * hierarchy rules.
     *
     * @param parentNode   The current JMeter tree node
     * @param childElement The element to be added
     * @return true if the node is compatible, false otherwise
     */
    public static boolean isNodeCompatible(JMeterTreeNode parentNode, TestElement childElement) {
        String parentNodeGuiClass = parentNode.getTestElement().getPropertyAsString(TestElement.GUI_CLASS);
        String childElementGuiClass = childElement.getPropertyAsString(TestElement.GUI_CLASS);

        log.info("========== Compatibility Check Start ==========");
        log.info("Parent element: {} (type: {}, GUI: {})",
                parentNode.getTestElement().getName(),
                parentNode.getTestElement().getClass().getSimpleName(),
                parentNodeGuiClass);
        log.info("Child element: {} (type: {}, GUI: {})",
                childElement.getName(),
                childElement.getClass().getSimpleName(),
                childElementGuiClass);

        // Infer parent component type using inheritance-based check (preferred)
        ComponentType parentType = inferComponentType(parentNode.getTestElement());
        if (parentType == null) {
            log.info("Parent type not found via TestElement, trying class name...");
            // Fallback to string-based inference
            String nodeType = parentNode.getTestElement().getClass().getSimpleName();
            parentType = inferComponentType(nodeType);
            if (parentType == null) {
                log.info("Parent type not found via class name, trying GUI class...");
                parentType = inferComponentType(parentNodeGuiClass);
            }
        }

        if (parentType == null) {
            log.warn("Could not determine parent component type - returning INCOMPATIBLE");
            log.info("========== Compatibility Check End (INCOMPATIBLE) ==========");
            return false;
        }

        log.info("Inferred parent type: {}", parentType);

        // Infer child component type
        ComponentType childType = inferComponentType(childElement);
        if (childType == null) {
            log.warn("Could not determine child component type - returning INCOMPATIBLE");
            log.info("========== Compatibility Check End (INCOMPATIBLE) ==========");
            return false;
        }

        log.info("Inferred child type: {}", childType);

        // Check compatibility using component types
        boolean compatible = isCompatibleUsingComponentTypes(parentType, childType);
        log.info("Compatibility result: {}", compatible ? "COMPATIBLE" : "INCOMPATIBLE");
        log.info("========== Compatibility Check End ==========");
        return compatible;
    }

    /**
     * Checks if a node is compatible with the given element type based on JMeter's
     * hierarchy rules.
     *
     * @param parentNode       The current JMeter tree node
     * @param childElementType The type of element to add
     * @return true if the node is compatible, false otherwise
     */
    private static boolean isNodeCompatible(JMeterTreeNode parentNode, String childElementType) {
        String parentNodeType = parentNode.getTestElement().getClass().getSimpleName();
        String parentNodeGuiClass = parentNode.getTestElement().getPropertyAsString(TestElement.GUI_CLASS);

        log.info("========== Compatibility Check Start (String) ==========");
        log.info("Parent element: {} (type: {}, GUI: {})",
                parentNode.getTestElement().getName(), parentNodeType, parentNodeGuiClass);
        log.info("Requested child element type: {}", childElementType);

        // Normalize element type for validation
        String normalizedType = normalizeElementType(childElementType);
        log.info("Normalized element type: {}", normalizedType);

        // Get child element's GUI class name
        ElementClassInfo classInfo = ELEMENT_CLASS_MAP.get(normalizedType);
        if (classInfo == null) {
            log.warn("Could not find class info for element type: {} - returning INCOMPATIBLE", normalizedType);
            log.info("========== Compatibility Check End (INCOMPATIBLE) ==========");
            return false;
        }

        String childGuiClass = classInfo.getGuiClassName();
        log.info("Child GUI class: {}", childGuiClass);

        // Infer parent component type
        ComponentType parentType = inferComponentType(parentNodeType);
        if (parentType == null) {
            log.info("Parent type not found via class name, trying GUI class...");
            parentType = inferComponentType(parentNodeGuiClass);
        }

        if (parentType == null) {
            log.warn("Could not determine parent component type - returning INCOMPATIBLE");
            log.info("========== Compatibility Check End (INCOMPATIBLE) ==========");
            return false;
        }

        log.info("Inferred parent type: {}", parentType);

        // Infer child component type from GUI class name
        ComponentType childType = inferComponentType(childGuiClass);
        if (childType == null) {
            log.warn("Could not determine child component type - returning INCOMPATIBLE");
            log.info("========== Compatibility Check End (INCOMPATIBLE) ==========");
            return false;
        }

        log.info("Inferred child type: {}", childType);

        // Check compatibility using component types
        boolean compatible = isCompatibleUsingComponentTypes(parentType, childType);
        log.info("Compatibility result: {}", compatible ? "COMPATIBLE" : "INCOMPATIBLE");
        log.info("========== Compatibility Check End (String) ==========");
        return compatible;
    }

    /**
     * Gets a user-friendly description of what parent node types a child element
     * can be added to.
     *
     * @param element The child element
     * @return A description of supported parent node types, or null if unable to
     *         determine
     */
    public static String getSupportedParentTypesDescription(TestElement element) {
        if (element == null) {
            return null;
        }

        // Infer child component type
        ComponentType childType = inferComponentType(element);
        if (childType == null) {
            return null;
        }

        // Find which parent types support this child type
        List<String> supportedParentTypes = new ArrayList<>();
        for (Map.Entry<ComponentType, Set<ComponentType>> entry : COMPATIBILITY_MATRIX.entrySet()) {
            ComponentType parentType = entry.getKey();
            Set<ComponentType> supportedChildTypes = entry.getValue();

            if (supportedChildTypes.contains(childType)) {
                supportedParentTypes.add(parentType.getName());
            }
        }

        if (supportedParentTypes.isEmpty()) {
            return "This element cannot be added to any parent node.";
        }

        // Build user-friendly description
        StringBuilder sb = new StringBuilder();
        sb.append("This element can only be added to: ");
        sb.append(String.join(", ", supportedParentTypes));
        sb.append(".");

        return sb.toString();
    }

    /**
     * Gets a user-friendly description of what child element types a node can
     * contain.
     *
     * @param node The JMeter tree node
     * @return A description of supported child element types, or null if unable to
     *         determine
     */
    public static String getSupportedChildTypesDescription(JMeterTreeNode node) {
        if (node == null || node.getTestElement() == null) {
            return null;
        }

        // Infer parent component type
        ComponentType parentType = inferComponentType(node.getTestElement());
        if (parentType == null) {
            // Fallback to string-based inference
            String nodeType = node.getTestElement().getClass().getSimpleName();
            parentType = inferComponentType(nodeType);
            if (parentType == null) {
                String parentNodeGuiClass = node.getTestElement().getPropertyAsString(TestElement.GUI_CLASS);
                parentType = inferComponentType(parentNodeGuiClass);
            }
        }

        if (parentType == null) {
            return null;
        }

        // Get supported child types
        Set<ComponentType> supportedChildTypes = COMPATIBILITY_MATRIX.get(parentType);
        if (supportedChildTypes == null || supportedChildTypes.isEmpty()) {
            return "This element cannot contain any child elements.";
        }

        // Convert component types to user-friendly descriptions
        StringBuilder sb = new StringBuilder();
        sb.append("This element can contain: ");

        List<String> descriptions = new ArrayList<>();
        for (ComponentType childType : supportedChildTypes) {
            descriptions.add(getComponentTypeDescription(childType));
        }
        sb.append(String.join(", ", descriptions));
        sb.append(".");

        return sb.toString();
    }

    /**
     * Gets a user-friendly description for a ComponentType.
     *
     * @param componentType The component type
     * @return A user-friendly description
     */
    private static String getComponentTypeDescription(ComponentType componentType) {
        if (componentType == null) {
            return "Unknown";
        }
        return componentType.getName();
    }

    /**
     * Gets a user-friendly description for a JMeter element type.
     *
     * @param elementType The element type (class name)
     * @return A user-friendly description of the element
     */
    public static String getElementDescription(String elementType) {
        if (elementType == null) {
            return "Unknown element type";
        }

        // Map common element types to descriptions
        switch (elementType.toLowerCase()) {
            case "httpsamplerproxy":
                return "HTTP Sampler allows you to send HTTP/HTTPS requests to a web server.";
            case "httpdefaults":
                return "HTTP Request Defaults lets you specify default values for HTTP Request samplers.";
            case "cookiemanager":
                return "HTTP Cookie Manager lets you control and manage HTTP cookies in your test.";
            case "headermanager":
                return "HTTP Header Manager lets you add or override HTTP request headers.";
            case "cachemanager":
                return "HTTP Cache Manager emulates browser cache behavior.";
            case "threadgroup":
                return "Thread Group defines a pool of users that will execute the test plan.";
            case "loopcontroller":
                return "Loop Controller lets you control how many times operations are executed.";
            case "ifcontroller":
                return "If Controller allows you to control whether test elements are executed based on a condition.";
            case "whilecontroller":
                return "While Controller repeatedly executes test elements while a condition is true.";
            case "foreachcontroller":
                return "ForEach Controller lets you loop through a set of variables.";
            case "transactioncontroller":
                return "Transaction Controller generates an additional sample which measures the overall time taken to execute.";
            case "timerwrapper":
                return "Timer controls the time JMeter waits between each request.";
            case "constanttimer":
                return "Constant Timer adds a fixed delay between requests.";
            case "uniformrandomtimer":
                return "Uniform Random Timer adds a random delay with a uniform distribution.";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer adds a random delay with a Gaussian distribution.";
            case "assertion":
                return "Assertion allows you to validate the response of a request.";
            case "responseassertionguiwrapper":
                return "Response Assertion lets you check the content of a server response.";
            case "jsrassertion":
                return "JSR223 Assertion allows you to create custom assertions using scripting languages.";
            case "xmlassertion":
                return "XML Assertion verifies that the response is a well-formed XML document.";
            case "jsonpathassertion":
                return "JSON Path Assertion extracts values from JSON responses for validation.";
            case "xpathassertionguiwrapper":
                return "XPath Assertion allows you to validate XML responses using XPath expressions.";
            case "durationassertion":
                return "Duration Assertion checks that a response was received within a given amount of time.";
            case "sizeassertion":
                return "Size Assertion verifies that the response contains the right number of bytes.";
            case "jsr223sampler":
                return "JSR223 Sampler allows you to create custom requests using scripting languages.";
            case "jdbcsampler":
                return "JDBC Request allows you to send SQL queries to a database.";
            case "ftpsampler":
                return "FTP Request allows you to send FTP requests to an FTP server.";
            case "javasampler":
                return "Java Request allows you to create a custom sampler using Java code.";
            case "ldapsampler":
                return "LDAP Request allows you to send requests to an LDAP server.";
            case "mailreader":
                return "Mail Reader Sampler allows you to read emails from a POP3/IMAP server.";
            case "smtpsampler":
                return "SMTP Sampler allows you to send emails via SMTP.";
            case "soapsampler":
                return "SOAP/XML-RPC Request allows you to send SOAP or XML-RPC requests.";
            case "tcpsampler":
                return "TCP Sampler allows you to send TCP requests.";
            case "testaction":
                return "Test Action allows you to pause or stop a test.";
            case "debugsampler":
                return "Debug Sampler shows JMeter variables and properties.";
            case "jsonsamplerproxy":
                return "JSON Request sends JSON requests to a server.";
            default:
                return "JMeter element of type: " + elementType;
        }
    }

    /**
     * Main method for testing the functionality.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: JMeterElementManager <elementType> [elementName]");
            System.out.println("Supported element types:");
            System.out.println(getSupportedElementTypes());
            return;
        }

        String elementType = args[0];
        String elementName = args.length > 1 ? args[1] : null;

        boolean success = addElement(elementType, elementName);

        if (success) {
            System.out.println("Successfully added " + elementType +
                    (elementName != null ? " named \"" + elementName + "\"" : "") +
                    " to the test plan.");
        } else {
            System.out.println("Failed to add " + elementType + " to the test plan.");
        }
    }

    /**
     * Status class for test plan readiness.
     */
    public static class TestPlanStatus {
        private final boolean ready;
        private final String errorMessage;

        public TestPlanStatus(boolean ready, String errorMessage) {
            this.ready = ready;
            this.errorMessage = errorMessage;
        }

        public boolean isReady() {
            return ready;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

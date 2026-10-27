package io.canvasmc.canvas.region;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Canvas region tests")
@IncludeTags("Region")
@SelectPackages("io.canvasmc.canvas.region")
@ExcludeClassNamePatterns(".*TestSuite")
@ConfigurationParameter(key = "TestSuite", value = "Region")
public class CanvasRegionTestSuite {
}

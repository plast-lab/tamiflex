rootProject.name = "tamiflex"

// Keep the historical directory names in place; map Gradle module names onto them
// so the migration touches build files, not the source-tree layout.
include(
    "normalizer",
    "playout-agent",
    "monitor-agent",
    "reporting-agent",
    "playin-agent",
    "booster",
    "database",
)

project(":normalizer").projectDir = file("Normalizer")
project(":playout-agent").projectDir = file("PlayOutAgent")
project(":monitor-agent").projectDir = file("MonitorAgent")
project(":reporting-agent").projectDir = file("ReportingAgent")
project(":playin-agent").projectDir = file("PlayInAgent")
project(":booster").projectDir = file("Booster")
project(":database").projectDir = file("TamiFlexDatabase")

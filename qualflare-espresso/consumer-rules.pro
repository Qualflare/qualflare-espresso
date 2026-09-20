# The listener is named only by a string, in the consumer's own build file:
#   testInstrumentationRunnerArguments["listener"] = "com.qualflare.espresso.QualflareRunListener"
# R8 sees no reference to it and removes it. The symptom is the worst kind: no
# report, no error, a green build. AndroidJUnitRunner reflects a PUBLIC NO-ARG
# constructor, so that has to survive too.
-keep public class com.qualflare.espresso.QualflareRunListener {
    public <init>();
}

# The metadata API is called from test code, which may itself be minified.
-keep public class com.qualflare.espresso.Qualflare {
    public *;
}
-keep public class com.qualflare.espresso.QualflareRule {
    public *;
}

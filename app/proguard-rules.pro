# The app is open source, so stable names are more valuable than obfuscation.
-dontobfuscate

# These classes are a durable Jackson JSON schema. Keep their fields,
# constructors, nested record types, and generic signatures intact so R8 does
# not change the schema or erase the element types of persisted lists.
-keep class in.izyum.bart.data.FollowedTripRecord { *; }
-keep class in.izyum.bart.data.FollowedTripRecord$* { *; }
-keep class in.izyum.bart.model.StationPair { *; }

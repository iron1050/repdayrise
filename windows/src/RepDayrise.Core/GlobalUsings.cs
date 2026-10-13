// habitId -> (epochDay -> value). Concrete types so a snapshot deserialized from JSON can be used directly.
global using EntryMap = System.Collections.Generic.Dictionary<long, System.Collections.Generic.Dictionary<long, double>>;

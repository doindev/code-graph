# Method implementation evidence

`find_implementations` and the opt-in `implementations` section of
`get_symbol_context` accept method/function symbols as well as types. Method
relationships are `OVERRIDES`, not `CALLS`: they do not invent call-site locations
or widen reference counts. Existing IDs are unchanged.

| Adapter | Verified declaration subset | Important exclusions |
|---|---|---|
| Java | Explicit local/imported ancestry, overload parameters, instantiated parent type parameters, visibility, covariant returns | Unknown/external types, method-generic substitutions, ambiguous environments, static/private/final/constructors |
| JS/TS | Module-resolved literal ancestry; TS exact non-generic parameter signatures; explicit instance methods | Dynamic extends, type-only/runtime confusion, unrelated same-name parents, accessor/static/private and unsupported optional/rest signatures |
| Kotlin / Scala | Explicit nominal parents and matching typed methods; language-specific override/open/trait rules | Generics and unknown/inferred signatures; final/private/static members |
| C# / C++ | Interface/virtual/override evidence, exact typed parameters; C++ qualifiers | Hiding, nonvirtual C++ methods, unresolved overloads/templates, unsafe signature substitutions |
| Swift / Dart | Protocol/interface or explicit class ancestry; Swift argument labels | Unsupported generics, static dispatch, Swift mutating/class requirements |
| Objective-C | Explicit interface/protocol ancestry, full selectors and instance/class scope | Runtime replacement, ambiguous categories and external protocols |
| Rust | Explicit trait impl for a resolved local receiver and exact signature | Blanket/generic impls, unresolved associated types and external traits |
| Go | Whole locally resolved interface method set, value/pointer receiver distinction | Partial method sets, unknown embedded methods/types and unverified build constraints |
| Python / Ruby | Literal local ancestry; Python C3, Ruby supported literal include order | Metaclasses, decorators, monkey patches, dynamic bases, prepend and ambiguous reopening |
| PHP | Literal local class/interface/trait declarations and exact types | Trait adaptations, private/static/constructor/destructor and unresolved qualified parents |

Confidence describes the **returned declaration evidence**, not exhaustive runtime
dispatch or proof that an empty result means no implementation. Dynamic-language
relationships retain explicit declaration-only evidence. Adapter work and ancestry
are bounded; exhausted/ambiguous paths do not publish guesses. Nominal adapters
inspect at most 32 ancestors and 512 lookup/candidate units per method; Java uses
its shared type-resolution budget. Memory and hybrid publication tests compare
edges before changes, after signature edits, after restoration and after deletion.

Fixtures: `JavaMethodRelationshipsTest`, `EcmaMethodRelationshipsTest`,
`NominalMethodsTest`, `MethodPublicationTest`, `CodeNavigationTest`, and
`SymbolContextToolTest`. Run the targeted reactor through
`scripts/Test-IsolatedReactor.ps1` when the user's application is running, so its
loaded class files are not overwritten.

Use indexed evidence directly for scoped declaration/implementation navigation
when it answers the question. Read source for implementation changes, unknown
signatures, dynamic behavior or stronger exhaustiveness requirements; do not
automatically repeat every result with a filesystem search.

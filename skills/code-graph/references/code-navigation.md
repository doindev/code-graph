# Code navigation and change evidence

Discover the server's actual tool schemas and select the exact project. Prefer
indexed locations for declarations, callers and dependencies; use local file tools
to read/edit the returned paths. Use local text search for literals, unsupported
syntax or files outside the index. Index absence is not proof of non-use.

- `get_file_outline`: page declarations in one project-relative file.
- `resolve_symbol_at_position`: supply 1-based line/UTF-16 column. Check the
  resolution and location-precision labels; containing-symbol results are not
  identifier resolution. Ambiguous overloads can return multiple candidates.
- `find_references`: indexed incoming edges may identify a token, an expression,
  an old line-only site, or only the containing declaration. Do not invent an exact
  occurrence for less precise results.
- `find_implementations`: current support is direct type inheritance/interfaces,
  not complete method overrides or runtime dispatch. Follow returned types when
  indirect relationships are needed.

Use cursors only with the same query/project/generation. Restart the query after
stale/expired cursor errors rather than combining pages from different indexes.
Do not retain an index generation indefinitely to keep pagination alive.

`analyze_change` and `find_affected_tests`, when present, accept explicit files,
symbol IDs or database object-name changes. A server may also accept a bounded
working-tree or exact `base`/`head` Git selection. Preserve resolved commits, source
generations, evidence paths, confidence and unresolved targets. Git-selected paths
are still evaluated against the current published graph, not a reconstructed
historical graph. If the server rejects a revision, inspect the desired diff with
existing local tools and label the graph evidence accurately.
Candidate tests are recommendations, not executed tests or a coverage guarantee.

Static SQL/JPA/MyBatis/Prisma/TypeORM mappings identify source locations and names.
Default naming strategies, reflection, computed metadata, dynamic strings and
derived query lineage may remain uncertain. A proposed database-name match does
not grant access to any database; obtain separately authorized catalog evidence.
Do not infer renames, safe drops, compatible datatypes or migration SQL from a
missing or similarly named code mapping.

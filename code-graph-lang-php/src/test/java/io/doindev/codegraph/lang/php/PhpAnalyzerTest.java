package io.doindev.codegraph.lang.php;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpAnalyzerTest {

    private final PhpAnalyzer analyzer = new PhpAnalyzer();

    private static final String SOURCE = """
            <?php

            namespace App\\Models;

            use App\\Contracts\\Persistable;
            use App\\Support\\Logger;

            class User extends BaseModel implements Persistable
            {
                private $name;
                public $email;

                public function save($validator, $flags)
                {
                    $repo = new UserRepository();
                    $repo->persist($this, $flags);
                    Logger::info("saved");
                    sanitize($this->name);
                    if ($flags > 0) {
                    }
                    return true;
                }
            }

            function helper($x)
            {
                return $x;
            }
            """;

    private FileFragment extract(String relPath, String content) {
        return analyzer.extract(new SourceFile(relPath, "php", content));
    }

    private static Node node(FileFragment fragment, NodeKind kind, String qualifiedName) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.id() instanceof SymbolId id
                        && id.qualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
    }

    private static RawRef call(FileFragment fragment, String name) {
        return fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.CALL && r.name().equals(name))
                .findFirst().orElse(null);
    }

    @Test
    void namespaceQualifiesTypesAndFunctions() {
        FileFragment fragment = extract("app/Models/User.php", SOURCE);

        Node type = node(fragment, NodeKind.TYPE, "App\\Models.User");
        assertNotNull(type, "TYPE node with namespace-qualified name");
        assertEquals("User", type.name());

        Node save = node(fragment, NodeKind.FUNCTION, "App\\Models.User.save");
        assertNotNull(save, "method_declaration with qualified name");
        assertEquals(2, ((SymbolId) save.id()).arity());

        Node helper = node(fragment, NodeKind.FUNCTION, "App\\Models.helper");
        assertNotNull(helper, "top-level function_definition");
        assertEquals(1, ((SymbolId) helper.id()).arity());
    }

    @Test
    void callsCarryNameReceiverAndArity() {
        FileFragment fragment = extract("app/Models/User.php", SOURCE);

        RawRef memberCall = call(fragment, "persist");
        assertNotNull(memberCall, "member call $repo->persist(...)");
        assertEquals("$repo", memberCall.receiverHint());
        assertEquals(2, memberCall.arity());

        RawRef scopedCall = call(fragment, "info");
        assertNotNull(scopedCall, "scoped call Logger::info(...)");
        assertEquals("Logger", scopedCall.receiverHint());
        assertEquals(1, scopedCall.arity());

        RawRef functionCall = call(fragment, "sanitize");
        assertNotNull(functionCall, "function call sanitize(...)");
        assertEquals(1, functionCall.arity());

        RawRef creation = call(fragment, "UserRepository");
        assertNotNull(creation, "object creation new UserRepository()");
        assertEquals(0, creation.arity());
    }

    @Test
    void usesAreCollectedAsWritten() {
        FileFragment fragment = extract("app/Models/User.php", SOURCE);
        assertTrue(fragment.imports().contains("App\\Contracts\\Persistable"),
                fragment.imports().toString());
        assertTrue(fragment.imports().contains("App\\Support\\Logger"),
                fragment.imports().toString());
    }

    @Test
    void extendsAndImplementsAreDistinguished() {
        FileFragment fragment = extract("app/Models/User.php", SOURCE);
        List<RawRef> supers = fragment.rawRefs().stream()
                .filter(r -> (r.kind() == RefKind.EXTENDS || r.kind() == RefKind.IMPLEMENTS)
                        && r.from() instanceof SymbolId id
                        && id.qualifiedName().equals("App\\Models.User"))
                .toList();
        assertEquals(2, supers.size(), supers.toString());
        assertTrue(supers.stream().anyMatch(
                r -> r.kind() == RefKind.EXTENDS && r.name().equals("BaseModel")));
        assertTrue(supers.stream().anyMatch(
                r -> r.kind() == RefKind.IMPLEMENTS && r.name().equals("Persistable")));
    }

    @Test
    void propertiesBecomeVariablesWithoutDollarSign() {
        FileFragment fragment = extract("app/Models/User.php", SOURCE);
        assertNotNull(node(fragment, NodeKind.VARIABLE, "App\\Models.User.name"),
                "property $name stripped of $");
        assertNotNull(node(fragment, NodeKind.VARIABLE, "App\\Models.User.email"),
                "property $email stripped of $");
    }

    @Test
    void qualifiedFunctionCallsStripLeadingBackslash() {
        String source = """
                <?php

                function run()
                {
                    \\strlen("abc");
                }
                """;
        FileFragment fragment = extract("app/run.php", source);
        RawRef call = call(fragment, "strlen");
        assertNotNull(call, "\\strlen(...) with leading backslash stripped");
        assertEquals(1, call.arity());
    }

    @Test
    void traitsInterfacesAndEnumsAreTypes() {
        String source = """
                <?php

                namespace App\\Support;

                trait Timestamps
                {
                }

                interface Cache
                {
                }

                enum Status
                {
                    case Active;
                }
                """;
        FileFragment fragment = extract("app/Support/types.php", source);
        assertNotNull(node(fragment, NodeKind.TYPE, "App\\Support.Timestamps"));
        assertNotNull(node(fragment, NodeKind.TYPE, "App\\Support.Cache"));
        assertNotNull(node(fragment, NodeKind.TYPE, "App\\Support.Status"));
    }
}

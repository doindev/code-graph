package io.doindev.codegraph.index.mapping;

import io.doindev.codegraph.index.*;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeDatabaseMappingsTest {
    @TempDir Path root;
    static List<Node> extract(String path,String code){var analyzer=Analyzers.discover().forPath(path);assertNotNull(analyzer);return analyzer.extract(new SourceFile(path,analyzer.languageId(),code)).declarations().stream().filter(n->n.kind()==NodeKind.DATABASE_MAPPING).toList();}
    static boolean has(List<Node> nodes,String table,String column){return nodes.stream().anyMatch(n->n.attrs().getOrDefault("table","").equals(table)&&n.attrs().getOrDefault("column","").equals(column));}
    @Test void staticSqlAliasesJoinsAndAmbiguityAreNotConfused(){
        var evidence=new MappingEvidence(new SourceFile("query.sql","sql",""));
        SqlMappings.extract(evidence,"SELECT u.id, o.id, id FROM auth.users u JOIN shop.orders o ON u.id=o.user_id WHERE u.active = true",0,"sql");
        assertTrue(has(evidence.nodes,"users","id"));assertTrue(has(evidence.nodes,"orders","id"));assertTrue(has(evidence.nodes,"orders","user_id"));
        assertTrue(evidence.nodes.stream().anyMatch(n->n.attrs().get("relationship").equals("unresolved_column")));
        assertFalse(evidence.nodes.stream().anyMatch(n->n.attrs().get("table").equals("u")));
    }
    @Test void javaJpaAndLiteralSqlPreserveLocationsWithoutValues(){
        var rows=extract("User.java","""
                import jakarta.persistence.*;
                @Entity @Table(name="users", schema="auth")
                class User {
                  @Id @Column(name="user_id", nullable=false) Long id;
                  String sql="SELECT u.email FROM auth.users u WHERE u.secret='do-not-retain'";
                  String dynamic="SELECT * FROM " + tableName;
                }
                """);
        assertTrue(has(rows,"users","user_id"));assertTrue(has(rows,"users","email"));
        assertTrue(rows.stream().anyMatch(n->n.attrs().containsKey("uncertainty")));
        assertFalse(rows.toString().contains("do-not-retain"));
        assertTrue(rows.stream().filter(n->n.attrs().get("column").equals("user_id")).allMatch(n->n.span().startLine()==4));
    }
    @Test void typescriptTypeormExplicitNamesAndUnknownNaming(){
        var rows=extract("User.ts","""
                import {Entity, PrimaryColumn, Column} from 'typeorm';
                @Entity({name: 'users', schema: 'auth'})
                export class User {
                  @PrimaryColumn({name: 'user_id', type: 'integer'}) id!: number;
                  @Column() displayName: string;
                }
                """);
        assertTrue(has(rows,"users","user_id"));assertTrue(has(rows,"users","displayName"));
        assertTrue(rows.stream().anyMatch(n->n.attrs().getOrDefault("uncertainty","").contains("naming")));
        assertTrue(has(extract("query.js","const sql = `SELECT u.id FROM auth.users u`;"),"users","id"));
        assertTrue(has(extract("Concat.java","class C { String sql=\"SELECT id \" + \"FROM users\"; }"),"users","id"));
    }
    @Test void prismaMappingsNeverIncludeDatasourceCredentials(){
        var rows=extract("schema.prisma","""
                datasource db { provider = "postgresql" url = "postgres://secret-password" }
                model User {
                  id Int @id @map("user_id")
                  nickname String?
                  @@map("users")
                  @@schema("auth")
                }
                """);
        assertTrue(has(rows,"users","user_id"));assertTrue(has(rows,"users","nickname"));
        assertFalse(rows.toString().contains("secret-password"));
        assertTrue(rows.stream().anyMatch(n->n.attrs().getOrDefault("nullable","").equals("true")));
    }
    @Test void mybatisStaticPreparedSqlAndDynamicXmlAreSeparate(){
        var rows=extract("Mapper.xml","""
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://must-not-be-requested.invalid/mapper.dtd">
                <mapper namespace="Example">
                  <select id="one">SELECT u.id FROM auth.users u WHERE u.id=#{id}</select>
                  <select id="dynamic">SELECT * FROM ${table}</select>
                </mapper>
                """);
        assertTrue(has(rows,"users","id"));assertTrue(rows.stream().anyMatch(n->n.attrs().get("relationship").equals("unresolved")));
        assertFalse(rows.toString().contains("must-not-be-requested"));
    }
    @Test void prismaRelationsFollowMappedModelAndColumnNames(){
        var rows=extract("schema.prisma","""
                model User {
                  id Int @id @map("user_id")
                  @@map("users")
                  @@schema("auth")
                }
                model Post {
                  id Int @id
                  authorId Int @map("author_id")
                  author User @relation(fields: [authorId], references: [id])
                  @@map("posts")
                }
                """);
        var fk=rows.stream().filter(n->n.attrs().get("relationship").equals("maps_foreign_key")).findFirst().orElseThrow();
        assertEquals("author_id",fk.attrs().get("column"));assertEquals("users",fk.attrs().get("targetTable"));assertEquals("auth",fk.attrs().get("targetSchema"));assertEquals("user_id",fk.attrs().get("targetColumn"));
    }
    @Test void mappingRecordsAreAtomicallyReplacedAndRemovedInBothStorageModes()throws Exception{
        for(boolean hybrid:new boolean[]{false,true}){
            Files.writeString(root.resolve("schema.prisma"),"model User {\n id Int\n @@map(\"users\")\n}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),hybrid,32L<<20)){
                workspace.fullIndexAll();var project=workspace.defaultProject();
                assertTrue(has(project.graph().allNodes(Set.of(NodeKind.DATABASE_MAPPING)),"users","id"));
                Files.writeString(root.resolve("schema.prisma"),"model Account {\n code String\n @@map(\"accounts\")\n}");
                project.indexer().applyChanges(List.of("schema.prisma"));
                assertFalse(has(project.graph().allNodes(Set.of(NodeKind.DATABASE_MAPPING)),"users","id"));
                assertTrue(has(project.graph().allNodes(Set.of(NodeKind.DATABASE_MAPPING)),"accounts","code"));
                Files.delete(root.resolve("schema.prisma"));project.indexer().applyChanges(List.of("schema.prisma"));
                assertTrue(project.graph().allNodes(Set.of(NodeKind.DATABASE_MAPPING)).isEmpty());
            }
        }
    }
}

// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspaceModel.storage

import com.intellij.testFramework.UsefulTestCase.assertEmpty
import com.intellij.testFramework.UsefulTestCase.assertOneElement
import com.intellij.workspaceModel.storage.entities.*
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityStorageBuilderImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replace by source test plan
 *
 * Plus sign means implemented
 *
 *   + Same entity modification
 *   + Add entity in remote builder
 *   + Remove entity in remote builder
 *   + Parent + child - modify parent
 *   + Parent + child - modify child
 *   + Parent + child - remove child
 *   + Parent + child - remove parent
 * - Parent + child - wrong source for parent nullable
 * - Parent + child - wrong source for parent not null
 * - Parent + child - wrong source for child nullable
 * - Parent + child - wrong source for child not null
 *   + Parent + child - wrong source for parent in remote builder
 *   + Parent + child - wrong source for child in remote builder
 *
 * Soft links
 *   + Change property of persistent id
 * - Change property of persistent id. Entity with link should be in wrong source
 * - Change property of persistent id. Entity with persistent id should be in wrong source (what should happen?)
 *
 *
 * different connection types
 * persistent id
 */
class ReplaceBySourceTest {
  @Test
  fun `add entity`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    val replacement = WorkspaceEntityStorageBuilderImpl.create()
    replacement.addSampleEntity("hello1", SampleEntitySource("1"))
    builder.replaceBySource({ it == SampleEntitySource("1") }, replacement)
    assertEquals(setOf("hello1", "hello2"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `remove entity`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val source1 = SampleEntitySource("1")
    builder.addSampleEntity("hello1", source1)
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    builder.replaceBySource({ it == source1 }, WorkspaceEntityStorageBuilderImpl.create())
    assertEquals("hello2", builder.singleSampleEntity().stringProperty)
    builder.assertConsistency()
  }

  @Test
  fun `remove and add entity`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val source1 = SampleEntitySource("1")
    builder.addSampleEntity("hello1", source1)
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    val replacement = WorkspaceEntityStorageBuilderImpl.create()
    replacement.addSampleEntity("updated", source1)
    builder.replaceBySource({ it == source1 }, replacement)
    assertEquals(setOf("hello2", "updated"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `multiple sources`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val sourceA1 = SampleEntitySource("a1")
    val sourceA2 = SampleEntitySource("a2")
    val sourceB = SampleEntitySource("b")
    builder.addSampleEntity("a", sourceA1)
    builder.addSampleEntity("b", sourceB)
    val replacement = WorkspaceEntityStorageBuilderImpl.create()
    replacement.addSampleEntity("new", sourceA2)
    builder.replaceBySource({ it is SampleEntitySource && it.name.startsWith("a") }, replacement)
    assertEquals(setOf("b", "new"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `work with different entity sources`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val sourceA1 = SampleEntitySource("a1")
    val sourceA2 = SampleEntitySource("a2")
    val parentEntity = builder.addParentEntity(source = sourceA1)
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.addNoDataChildEntity(parentEntity = parentEntity, source = sourceA2)
    builder.replaceBySource({ it == sourceA2 }, replacement)
    assertEquals(1, builder.toStorage().entities(ParentEntity::class.java).toList().size)
    assertEquals(1, builder.toStorage().entities(NoDataChildEntity::class.java).toList().size)
    builder.assertConsistency()
  }

  @Test
  fun `empty storages`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val builder2 = WorkspaceEntityStorageBuilderImpl.create()

    builder.replaceBySource({ true }, builder2)
    assertTrue(builder.collectChanges(
      WorkspaceEntityStorageBuilderImpl.create()).isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `replace with empty storage`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    builder.addSampleEntity("data1")
    builder.addSampleEntity("data2")
    builder.resetChanges()
    val originalStorage = builder.toStorage()

    builder.replaceBySource({ true }, WorkspaceEntityStorageBuilderImpl.create())
    val collectChanges = builder.collectChanges(originalStorage)
    assertEquals(1, collectChanges.size)
    assertEquals(2, collectChanges.values.single().size)
    assertTrue(collectChanges.values.single().all { it is EntityChange.Removed<*> })
    builder.assertConsistency()
    assertTrue(builder.entities(SampleEntity::class.java).toList().isEmpty())
  }

  @Test
  fun `add entity with false source`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    builder.resetChanges()
    val replacement = WorkspaceEntityStorageBuilderImpl.create()
    replacement.addSampleEntity("hello1", SampleEntitySource("1"))
    builder.replaceBySource({ false }, replacement)
    assertEquals(setOf("hello2"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    assertTrue(builder.collectChanges(
      WorkspaceEntityStorageBuilderImpl.create()).isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `entity modification`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val entity = builder.addSampleEntity("hello2")
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.modifyEntity(ModifiableSampleEntity::class.java, entity) {
      stringProperty = "Hello Alex"
    }
    builder.replaceBySource({ true }, replacement)
    assertEquals(setOf("Hello Alex"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `adding entity in builder`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.addSampleEntity("myEntity")
    builder.replaceBySource({ true }, replacement)
    assertEquals(setOf("myEntity"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `removing entity in builder`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val entity = builder.addSampleEntity("myEntity")
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.removeEntity(entity)
    builder.replaceBySource({ true }, replacement)
    assertTrue(builder.entities(SampleEntity::class.java).toList().isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - modify parent`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val parent = builder.addParentEntity("myProperty")
    builder.addChildEntity(parent, "myChild")

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.modifyEntity(ModifiableParentEntity::class.java, parent) {
      parentProperty = "newProperty"
    }

    builder.replaceBySource({ true }, replacement)

    val child = assertOneElement(builder.entities(ChildEntity::class.java).toList())
    assertEquals("newProperty", child.parent.parentProperty)
    assertOneElement(builder.entities(ParentEntity::class.java).toList())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - modify child`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.modifyEntity(ModifiableChildEntity::class.java, child) {
      childProperty = "newProperty"
    }

    builder.replaceBySource({ true }, replacement)

    val updatedChild = assertOneElement(builder.entities(ChildEntity::class.java).toList())
    assertEquals("newProperty", updatedChild.childProperty)
    assertEquals(updatedChild, assertOneElement(builder.entities(ParentEntity::class.java).toList()).children.single())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - remove parent`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.removeEntity(parent)

    builder.replaceBySource({ true }, replacement)

    assertEmpty(builder.entities(ChildEntity::class.java).toList())
    assertEmpty(builder.entities(ParentEntity::class.java).toList())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - remove child`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.removeEntity(child)

    builder.replaceBySource({ true }, replacement)

    assertEmpty(builder.entities(ChildEntity::class.java).toList())
    assertOneElement(builder.entities(ParentEntity::class.java).toList())
    assertEmpty(builder.entities(ParentEntity::class.java).single().children.toList())
    builder.assertConsistency()
  }

  @Test(expected = IllegalStateException::class)
  fun `fail - child and parent - different source for parent`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    val parent = replacement.addParentEntity("myProperty", source = AnotherSource)
    val child = replacement.addChildEntity(parent, "myChild")

    builder.replaceBySource({ it is SampleEntitySource }, replacement)
  }

  @Test
  fun `child and parent - different source for child`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    val parent = replacement.addParentEntity("myProperty")
    val child = replacement.addChildEntity(parent, "myChild", source = AnotherSource)

    builder.replaceBySource({ it is SampleEntitySource }, replacement)

    assertEmpty(builder.entities(ChildEntity::class.java).toList())
    assertOneElement(builder.entities(ParentEntity::class.java).toList())
    assertEmpty(builder.entities(ParentEntity::class.java).single().children.toList())
    builder.assertConsistency()
  }

  @Test
  fun `entity with soft reference`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val named = builder.addNamedEntity("MyName")
    val linked = builder.addWithSoftLinkEntity(named.persistentId())
    builder.resetChanges()
    builder.assertConsistency()

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.modifyEntity(ModifiableNamedEntity::class.java, named) {
      this.name = "NewName"
    }

    replacement.assertConsistency()

    builder.replaceBySource({ true }, replacement)
    assertEquals("NewName", assertOneElement(builder.entities(NamedEntity::class.java).toList()).name)
    assertEquals("NewName", assertOneElement(builder.entities(WithSoftLinkEntity::class.java).toList()).link.presentableName)

    builder.assertConsistency()
  }

  @Test
  fun `entity with soft reference remove reference`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val named = builder.addNamedEntity("MyName")
    val linked = builder.addWithListSoftLinksEntity("name", listOf(named.persistentId()))
    builder.resetChanges()
    builder.assertConsistency()

    val replacement = WorkspaceEntityStorageBuilderImpl.from(builder)
    replacement.modifyEntity(ModifiableWithListSoftLinksEntity::class.java, linked) {
      this.links = emptyList()
    }

    replacement.assertConsistency()

    builder.replaceBySource({ true }, replacement)

    builder.assertConsistency()
  }

  @Test
  fun `replace by source with composite id`() {
    val builder = WorkspaceEntityStorageBuilderImpl.create()
    val replacement = WorkspaceEntityStorageBuilderImpl.create()
    val namedEntity = replacement.addNamedEntity("MyName")
    val composedEntity = replacement.addComposedIdSoftRefEntity("AnotherName", namedEntity.persistentId())
    replacement.addWithSoftLinkEntity(composedEntity.persistentId())

    replacement.assertConsistency()
    builder.replaceBySource({ true }, replacement)
    builder.assertConsistency()

    assertOneElement(builder.entities(NamedEntity::class.java).toList())
    assertOneElement(builder.entities(ComposedIdSoftRefEntity::class.java).toList())
    assertOneElement(builder.entities(WithSoftLinkEntity::class.java).toList())
  }

  /*
    Not sure if this should work this way. We can track persistentId changes in builder, but what if we perform replaceBySource with storage?
    @Test
    fun `entity with soft reference - linked has wrong source`() {
      val builder = PEntityStorageBuilder.create()
      val named = builder.addNamedEntity("MyName")
      val linked = builder.addWithSoftLinkEntity(named.persistentId(), AnotherSource)
      builder.resetChanges()
      builder.assertConsistency()

      val replacement = PEntityStorageBuilder.from(builder)
      replacement.modifyEntity(ModifiableNamedEntity::class.java, named) {
        this.name = "NewName"
      }

      replacement.assertConsistency()

      builder.replaceBySource({ it is MySource }, replacement)
      assertEquals("NewName", assertOneElement(builder.entities(NamedEntity::class.java).toList()).name)
      assertEquals("NewName", assertOneElement(builder.entities(WithSoftLinkEntity::class.java).toList()).link.presentableName)

      builder.assertConsistency()
    }
  */
}
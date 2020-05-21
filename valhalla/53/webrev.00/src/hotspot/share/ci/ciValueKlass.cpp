/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "ci/ciField.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "ci/ciValueKlass.hpp"
#include "oops/valueKlass.inline.hpp"

int ciValueKlass::compute_nonstatic_fields() {
  int result = ciInstanceKlass::compute_nonstatic_fields();
  assert(super() == NULL || !super()->has_nonstatic_fields(), "a value type must not inherit fields from its superclass");

  // Compute declared non-static fields (without flattening of value type fields)
  GrowableArray<ciField*>* fields = NULL;
  GUARDED_VM_ENTRY(fields = compute_nonstatic_fields_impl(NULL, false /* no flattening */);)
  Arena* arena = CURRENT_ENV->arena();
  _declared_nonstatic_fields = (fields != NULL) ? fields : new (arena) GrowableArray<ciField*>(arena, 0, 0, 0);
  return result;
}

// Offset of the first field in the value type
int ciValueKlass::first_field_offset() const {
  GUARDED_VM_ENTRY(return to_ValueKlass()->first_field_offset();)
}

// Returns the index of the field with the given offset. If the field at 'offset'
// belongs to a flattened value type field, return the index of the field
// in the flattened value type.
int ciValueKlass::field_index_by_offset(int offset) {
  assert(contains_field_offset(offset), "invalid field offset");
  int best_offset = 0;
  int best_index = -1;
  // Search the field with the given offset
  for (int i = 0; i < nof_declared_nonstatic_fields(); ++i) {
    int field_offset = _declared_nonstatic_fields->at(i)->offset();
    if (field_offset == offset) {
      // Exact match
      return i;
    } else if (field_offset < offset && field_offset > best_offset) {
      // No exact match. Save the index of the field with the closest offset that
      // is smaller than the given field offset. This index corresponds to the
      // flattened value type field that holds the field we are looking for.
      best_offset = field_offset;
      best_index = i;
    }
  }
  assert(best_index >= 0, "field not found");
  assert(best_offset == offset || _declared_nonstatic_fields->at(best_index)->type()->is_valuetype(), "offset should match for non-VTs");
  return best_index;
}

// Are arrays containing this value type flattened?
bool ciValueKlass::flatten_array() const {
  GUARDED_VM_ENTRY(return to_ValueKlass()->flatten_array();)
}

// Can this value type be returned as multiple values?
bool ciValueKlass::can_be_returned_as_fields() const {
  GUARDED_VM_ENTRY(return to_ValueKlass()->can_be_returned_as_fields();)
}

// Can this value type be scalarized?
bool ciValueKlass::is_scalarizable() const {
  GUARDED_VM_ENTRY(return to_ValueKlass()->is_scalarizable();)
}

// When passing a value type's fields as arguments, count the number
// of argument slots that are needed
int ciValueKlass::value_arg_slots() {
  int slots = 0;
  for (int j = 0; j < nof_nonstatic_fields(); j++) {
    ciField* field = nonstatic_field_at(j);
    slots += type2size[field->type()->basic_type()];
  }
  return slots;
}

// Offset of the default oop in the mirror
int ciValueKlass::default_value_offset() const {
  GUARDED_VM_ENTRY(return to_ValueKlass()->default_value_offset();)
}

ciInstance* ciValueKlass::default_value_instance() const {
  GUARDED_VM_ENTRY(
    oop default_value = to_ValueKlass()->default_value();
    return CURRENT_ENV->get_instance(default_value);
  )
}

ciInstance* ciValueKlass::inline_mirror_instance() const {
  GUARDED_VM_ENTRY(
    oop mirror = to_ValueKlass()->java_mirror();
    return CURRENT_ENV->get_instance(mirror);
  )
}

ciInstance* ciValueKlass::indirect_mirror_instance() const {
  GUARDED_VM_ENTRY(
    oop mirror = to_ValueKlass()->ref_mirror();
    return CURRENT_ENV->get_instance(mirror);
  )
}

bool ciValueKlass::contains_oops() const {
  GUARDED_VM_ENTRY(return get_ValueKlass()->contains_oops();)
}

Array<SigEntry>* ciValueKlass::extended_sig() const {
  GUARDED_VM_ENTRY(return get_ValueKlass()->extended_sig();)
}

address ciValueKlass::pack_handler() const {
  GUARDED_VM_ENTRY(return get_ValueKlass()->pack_handler();)
}

address ciValueKlass::unpack_handler() const {
  GUARDED_VM_ENTRY(return get_ValueKlass()->unpack_handler();)
}

ValueKlass* ciValueKlass::get_ValueKlass() const {
  GUARDED_VM_ENTRY(return to_ValueKlass();)
}

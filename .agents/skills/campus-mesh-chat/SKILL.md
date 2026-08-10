---
name: campus-mesh-chat
description: Project-specific architectural constraints, privacy guidelines, and operational rules for the Campus Mesh Chat BitChat fork.
---

# Campus Mesh Chat Project Skill

- This is a fork of BitChat Android.
- Preserve the existing BLE mesh architecture unless there is a documented reason to change it.
- Do not remove encryption without explicit justification.
- Do not introduce servers or cloud dependencies.
- Do not introduce GPS/location tracking.
- Festival channels are fixed.
- SOS messages have priority handling.
- Organizer broadcasts are read-only for ordinary users.
- Test changes on multiple physical Android devices.
- Never assume BLE behavior from emulator testing.
- Run the existing test/build system before and after major changes.
- Keep upstream BitChat changes separable from campus-specific changes.

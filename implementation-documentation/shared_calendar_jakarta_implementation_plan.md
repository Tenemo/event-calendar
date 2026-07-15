# Shared calendar implementation plan

**Revision:** implementation and production deployment current as of 2026-07-15

**Purpose:** Sequence repository work for the shared-calendar application.

The project specification is the authoritative source for product behavior, architecture, security, testing, and operations. Milestones contain implementation steps only; they must not introduce or redefine requirements.

## Implementation order

1. M0: project foundation
2. M1: persistence and security core
3. M2: calendar and account workflows
4. M3: production readiness

All four milestones have been implemented. Extend the existing milestone that owns a new requirement instead of placing specification prose in a milestone.

## Working method

1. Read the project specification before changing implementation.
2. Select the milestone that owns the affected layer.
3. Add or revise concrete implementation steps in that milestone.
4. Implement the smallest complete vertical slice.
5. Run the milestone's verification steps.
6. Update the public README when setup, deployment, environment variables, roles, backup and restore, limitations, or operational behavior changes.

## Source boundary

This folder is private planning material. Public documentation and product code must use final product language without exposing planning-only labels or file names.

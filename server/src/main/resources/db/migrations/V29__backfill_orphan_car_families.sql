-- ============================================================
-- Orphan car family sweep.
--
-- V23's Part 2 singleton sweep guarantees family_id IS NOT NULL for every
-- car_models row that existed at the time it ran, and for anything inserted
-- since through the normal catalog-seeding path. But car_models.family_id
-- is still a nullable column with no DB-level guarantee going forward — any
-- row inserted without an explicit family_id (or a family created with a
-- brand whose casing doesn't match car_models.brand, e.g. CarFamilyService's
-- unnormalized createFamily) stays permanently, silently ineligible for
-- every challenge (ChallengeProgressDAO.evaluatePostContribution matches on
-- car_models.family_id = challenges.target_family_id).
--
-- Same fix as V23 Part 2, re-run once as its own migration: every car_models
-- row still without a family_id gets its own singleton family named after
-- itself. Idempotent — INSERT uses ON CONFLICT DO NOTHING, UPDATE only
-- touches rows still unassigned.
-- ============================================================

INSERT INTO car_families (brand, name)
SELECT brand, model FROM car_models WHERE family_id IS NULL
ON CONFLICT (brand, name) DO NOTHING;

UPDATE car_models cm
   SET family_id = cf.id
  FROM car_families cf
 WHERE cm.family_id IS NULL
   AND cf.brand = cm.brand
   AND cf.name = cm.model;

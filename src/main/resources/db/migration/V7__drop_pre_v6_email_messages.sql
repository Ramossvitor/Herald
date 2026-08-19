-- V6 copied every email into the shared outbox and parked the old table under
-- email_messages_pre_v6, so that a rollback during that deploy was a rename
-- rather than a reconstruction. That window is closed once this runs: from
-- here, going back before V6 needs a compensating migration written by hand.
--
-- Nothing reads the table -- V6 verified the copy row for row, and every query
-- in the application has been on `messages` since.

DROP TABLE IF EXISTS email_messages_pre_v6;

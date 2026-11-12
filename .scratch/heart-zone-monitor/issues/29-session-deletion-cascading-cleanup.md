# 29: Session Deletion & Cascading Cleanup

**What to build:** The session deletion action on the Session Detail screen with double-confirmation dialog, cascading deletion of all associated 1 Hz samples from the database, and navigation return.

**Blocked by:** 25 (Session Detail View: Telemetry & Zone Distribution)

**Status:** ready-for-agent

- [ ] Provides a "Delete Session" button in the bottom action bar of the Session Detail view.
- [ ] Tapping opens an alert dialog requiring explicit confirmation: *"Are you sure you want to delete this session? This action cannot be undone."*.
- [ ] On confirmation, executes `SessionDao.deleteSession(sessionId)`.
- [ ] Verifies database foreign key cascade deletes all related `heart_rate_samples` rows cleanly.
- [ ] Pops back to the session history screen and displays a confirmation snackbar.
- [ ] Automated tests verify cascading deletion and backstack navigation.

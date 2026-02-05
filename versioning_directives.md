# Application Versioning Strategy

This project adheres to a **Major.Minor.Patch** versioning system, commonly known as [Semantic Versioning (SemVer)](https://semver.org/).

## Version Format: `vX.Y.Z`

- **Major (X)**: Incremented for significant milestones, major overhauls, or breaking changes that fundamentally alter the user experience or architecture.
- **Minor (Y)**: Incremented for new features or substantial improvements that are compatible with the current major version.
- **Patch (Z)**: Incremented for bug fixes, performance improvements, and minor tweaks that do not add significant new features.

## Versioning Workflow

1.  **Development**: Features and fixes are developed and tested.
2.  **Release**:
    -   **Update What's New:** Open `app/src/main/assets/changelog.json` and add a new entry for this version. This ensures users see the "What's New" dialog on first launch after update.

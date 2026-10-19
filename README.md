# Podcast Player

Podcast Player is a **Brierfield Labs** Android podcast player, developed from the
open-source [Pocket Casts Android](https://github.com/Automattic/pocket-casts-android).
It retains Pocket Casts account sign-in and supported web synchronisation while
prioritising oldest-first listening, concise podcast controls and useful
Podcast Addict-style features.

This is an independent fork; it is **not affiliated with or endorsed by**
Automattic or Pocket Casts. The upstream project remains available for reviewed
changes. The original source acknowledgements and
[MPL-2.0 licence](LICENSE.md) continue to apply.

- [Roadmap and accepted behaviour](ROADMAP.md)
- [Feature issues](https://github.com/brierfieldlabs/podcast-player/issues)
- [Pending development PRs](https://github.com/brierfieldlabs/podcast-player/pulls)
- Last trial APK: **Podcast Player v1.2.0**, reported on a phone as
  **PodcastGPT** because the Android build-variant launcher label was missed.
- Correction candidate: **v1.2.1**, with launcher, application, and Android
  intent chooser labels set to **Podcast Player**. No data migration involved.
  Version numbers are semantic;
  development commits do not automatically result in APK builds or releases.

**Protect existing installs:** Keep the established Android application ID and
signing certificate so installed versions and sync accounts continue to work.
Never import real Podcast Addict history until the user explicitly approves it.

The build instructions below are inherited from the upstream repository and are
for developers. Our full APK build workflow is disabled during feature work.

## Build Instructions

1. Make sure you've installed [Android Studio](https://developer.android.com/studio/index.html).
2. In Android Studio, open the project from the local repository.
3. Go to Tools → Device Manager and create an emulated device.
4. Go to Run → Edit Configurations… and create an Android App configuration. 
5. Select the module "pocketcasts-android.app.main".
6. Run.

## Build and Test

To build, install, and test the project from the command line:

    $ ./gradlew :app:assembleDebugProd          # assemble the debug .apk
    $ ./gradlew :app:installDebugProd           # install the debug .apk to a connected device
    $ ./gradlew :app:testDebugUnitTest          # assemble, install and run unit tests
    $ ./gradlew :app:connectedDebugAndroidTest  # assemble, install and run Android tests

## Directory structure
    .
    ├── app                    # Mobile app
    ├── automotive             # Automotive app
    ├── modules
    │   ├── features
    │   │   ├── account        # Create account and sign in pages.
    │   │   ├── cartheme       # Automotive resources needed for the account pages.
    │   │   ├── discover       # Discover section.
    │   │   ├── endofyear      # End of year stats.
    │   │   ├── filters        # Filters section.
    │   │   ├── navigation     # Navigation utilities.
    │   │   ├── player         # Full screen player
    │   │   ├── podcasts       # Podcasts section.
    │   │   ├── profile        # Profile section.
    │   │   ├── search         # Search pages.
    │   │   ├── settings       # Settings pages.
    │   │   └── taskerplugin   # Plugin for integration with Tasker app.
    │   └── services
    │       ├── analytics      # Analytics code.
    │       ├── compose        # Shared Compose code.
    │       ├── images         # Image resources.
    │       ├── localization   # Contains the strings in English and localized strings from GlotPress. 
    │       ├── model          # The database logic and entities. Also transfer objects required which aren't stored in the database.
    │       ├── preferences    # Stores the user preferences and configuration settings.
    │       ├── repositories   # Provides accessing to the data from the 'servers' and 'model' modules.
    │       ├── servers        # Provides the network calls to the servers. The UI layer should access these through the 'repositories' module.
    │       ├── ui             # Shared UI code for the 'compose' and 'views' modules. This includes the themes.
    │       ├── utils          # Utility classes.
    │       └── views          # Shred Only the old views code.

The hierarchy of the modules in the project are reflected in [this diagram](docs/module-diagram.png).

## Contributing

Read our [Contributing Guide](CONTRIBUTING.md) to learn about reporting issues, contributing code, and more ways to contribute.

## Documentation

- [Coding Style](docs/coding-style.md) - guidelines and validation and auto-formatting tools
- [Pull Request Guidelines](docs/pull-request-guidelines.md) - branch naming and how to write good pull requests
- [Translations](docs/translations.md) - how the app gets translated and how to contribute translations

## Signing a Release

To build a _signed_ release, add these lines to your local.properties file

    signingKeyStoreFile=/Users/username/git/secret.keystore
    signingKeyStorePassword=
    signingKeyAlias=
    signingKeyPassword=

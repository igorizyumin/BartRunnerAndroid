# ![Logo](https://raw.github.com/dougkeen/BartRunnerAndroid/master/featuredGraphic.png)

This is a fork and AI-assisted rewrite of Doug Keen's original BART Runner app. The objective
is to modernize the app while maintaining its best parts -- a simple and fast UI and a minimum
of annoyances.

This project is not affiliated with BART in any way.

## Architecture

The production UI is built with Jetpack Compose. Screen state is owned by lifecycle-aware
ViewModels and collected with lifecycle-aware Compose APIs; the old XML/View screen layer is no
longer part of the application.

## Reporting bugs/requesting features
Please file bugs and suggestions as GitHub issues.

## Developed by
Igor Izyumin - igor.izyumin@gmail.com
Original by Doug Keen - doug@dougkeen.com

# Special thanks to

* Victor Stuber for some amazing work on the feature graphic and the app icon
* All pull request contributors, who are keeping this project alive!

## License

    Copyright 2026 Igor Izyumin
    Copyright 2012-2026 Doug Keen

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

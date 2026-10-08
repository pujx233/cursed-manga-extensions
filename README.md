# ExHentai Watched & Popular Test

Tachimanga extension repository: [https://raw.githubusercontent.com/pujx233/cursed-manga-extensions/exhentai-test-repo/index.min.json](https://raw.githubusercontent.com/pujx233/cursed-manga-extensions/exhentai-test-repo/index.min.json)

Version: **1.4.31**. This repository contains only the E-Hentai extension, with its existing source IDs and 17 languages.

- Popular loads the website's `/popular` list.
- Search filters → Gallery list → Watched tags loads the website's `/watched` list using the account configured in the extension.
- Gallery list also offers All galleries and Favorites as mutually exclusive choices.
- Pagination follows the website's Next URL, with separate cursors for search and latest updates.
- Gallery parsing supports the website's table and thumbnail layouts.

[Source branch](https://github.com/pujx233/cursed-manga-extensions/tree/feat/exhentai-watched-popular) · [Exact source commit](https://github.com/pujx233/cursed-manga-extensions/tree/df70e1faa1519d22793054fafe30199daffbd577)

Based on [cursed-manga-extensions](https://github.com/yuzono/cursed-manga-extensions). Source and binaries are distributed under the included Apache License 2.0.

Validation: release assembly, release lint, formatting checks and 8 unit tests passed. Installation and browsing in Tachimanga on an iPhone still require device testing.

### Node version & package manager

It is recommended to use [asdf](https://github.com/asdf-vm/asdf) for Node version management, the version this project uses is specified in `.tool-versions` file.

Quick instructions how to install asdf and node plugin:
```
brew install asdf
asdf plugin add nodejs https://github.com/asdf-vm/asdf-nodejs.git
```

More details [here](https://asdf-vm.com/guide/getting-started.html)

This project uses [pnpm](https://pnpm.io/) as package manager, you can install it like this:

```
curl -fsSL https://get.pnpm.io/install.sh | sh -
```

### Install dependencies

```bash
pnpm install
```

### Dev server
Serve with hot reload at <http://localhost:3000>

```bash
pnpm run dev
```

### Lint

```bash
pnpm run lint
```

### Typecheck

```bash
pnpm run typecheck
```

### Build

```bash
pnpm run build
```

### Test

```bash
pnpm run test
```

View and interact with tests via UI:

```bash
pnpm run test:ui
```

Testing lib cheat-sheet: https://testing-library.com/docs/react-testing-library/cheatsheet


### Intellij Setup

- Make sure you have enabled `Use paths relative to tsconfig.json` option in `Settings | Editor | Code Style | TypeScript`
- Make sure you have enabled `Automatic Prettier configuration` and `Run on save` in `Languages & Frameworks | JavaScript | Prettier`

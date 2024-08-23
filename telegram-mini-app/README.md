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
Serve with hot reload at <http://localhost:3001>

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

### Intellij Setup

- Make sure you have enabled `Use paths relative to tsconfig.json` option in `Settings | Editor | Code Style | TypeScript`
- Make sure you have enabled `Automatic Prettier configuration` and `Run on save` in `Languages & Frameworks | JavaScript | Prettier`

### Testing in Telegram

1. Signup for a free [ngrock](https://ngrok.com/) account, save your auth token in `~/Library/Application\ Support/ngrok/ngrok.yml` and create a domain via ngrock dashboard
2. Create a personal bot for yourself using [BotFather](https://t.me/botfather), send command `/newbot` and save your bot token somewhere
3. Create an app using `/newapp` command and attach it to your bot. When asked for web app url send it your ngrock domain from step 1 prefixed with `https://`
4. Send `/mybots` command, select your bot and press `Configure menu button` button. When asked for url send it the same url as in step 2
5. Run backend with TELEGRAM_BOT_TOKEN env variable, e.g: `TELEGRAM_BOT_TOKEN=<token from step 2> make run_backend`
6. Run `vite` web app dev server using `pnpm run dev`
7. Run `ngrock` to expose dev server to the internet: `ngrok http --domain=<your ngrock domain> 3001`
8. Start a conversation with your bot by sending `/start` command
9. You should now see an app button in the message entry widget - this is how you launch the mini app

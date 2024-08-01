/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx,css}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', ...defaultTheme.fontFamily.sans],
        content: ['Rubik', ...defaultTheme.fontFamily.sans]
      },
      fontSize: {
        'xxs': '0.625rem',  // 10px
        'xxxs': '0.5rem',   // 8px
      },
      keyframes: {
        swivel: {
          'from': {transform: 'rotateY(0deg)'},
          'to': {transform: 'rotateY(180deg)'}
        },
        gameOrangeBlink: {
          '0%, 100%': { backgroundColor: 'transparent' },
          '50%': { background: 'linear-gradient(to bottom, #FFA857, #FF710B)', opacity: 0.7 },
        },
      },
      animation: {
        swivel: 'swivel 0.5s linear',
        gameOrangeBlink: 'gameOrangeBlink 1.5s steps(1, end) infinite',
      }
    },
    colors: {
      primary4: "#FFA337",
      primary5: "#FF8A00",
      blue5: "#426d9e",
      white: "#FFF",
      black: "#111",
      transparent: "transparent",

      // reaction game colors
      gameOrange: "#FFA337",
      gameBlue: "#2288E7",
      gameBlack: "#1E1E1E",
      gameRed: "#FF7169",
      gameDisabled: "#646161",

      // new colors
      modalBlue: "#211E5B",
      mediumBlue: "#19173D",
      darkBlue: "#110F31",
      orangeStart: "#FF8E25",
      orangeStop: "#F6791F",
      brightOrange: "#FF8A00",
      dullOrange: "#A75A00",
      gameBlueStart: "#00C2FF",
      gameBlueStop: "#57C3FF",
    },
  },
  plugins: [
    require('@tailwindcss/forms')
  ],
}

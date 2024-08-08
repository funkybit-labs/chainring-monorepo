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
        wordArtSlide: {
          '0%': {transform: 'translateX(300px)'},
          '75%': {transform: 'translateX(-45px)'},
          '85%': {transform: 'translateX(15px)'},
          '90%': {transform: 'translateX(-10px)'},
          '95%': {transform: 'translateX(10px)'},
          '97%': {transform: 'translateX(-5px)'},
          '100%': {transform: 'translateX(0px)'}
        }
      },
      animation: {
        swivel: 'swivel 0.5s linear',
        gameOrangeBlink: 'gameOrangeBlink 1.5s steps(1, end) infinite',
        wordArtSlide: 'wordArtSlide 0.4s ease-out'
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

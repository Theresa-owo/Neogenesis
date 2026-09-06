package.path = package.path .. ";./lua/?.lua;./lua/screens/?.lua;"
local main_menu = require("main_menu")
main_menu.register()
-- screens self-register on require (open:<id> routes to them)
require("screens.settings")
require("screens.singleplayer")
require("screens.multiplayer")

package.path = package.path .. ";./lua/?.lua;"
local main_menu = require("main_menu")
main_menu.register()

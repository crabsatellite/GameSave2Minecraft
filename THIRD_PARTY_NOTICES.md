# Third-party boundaries

Minecraft and NeoForge are runtime/toolchain dependencies. They are not claimed
as authored framework work. Gradle and the Gradle wrapper retain their upstream
license. The supplied wrapper JAR is build tooling, not a game binary. Gson and
other dependencies are resolved through the declared toolchain.

The method uses [REA](https://github.com/morluto/rea) as an external inspection
tool and acknowledges [Universal Modder](https://github.com/rehan-remade/universal-modder)
as closely related agent-modding and cross-game work. Neither tool's source is
vendored here. [MineCity 2000](https://github.com/jgosar/mine-city-2000) is a
save-to-Minecraft precedent. [KindredSchematics](https://github.com/Odjit/KindredSchematics)
informed the case's source-game building relationship investigation. The paper
provides source citations. These acknowledgements do not relicense those projects.

OpenGL and Minecraft/NeoForge APIs provide implementation interfaces. The
generic runtime shaders and component code are author-owned implementation
code; imported source-game shader packages and artwork are not supplied.

The names of source games identify the discussed cases and dependencies.
This project is an independent research/software artifact and is not an
official product of their publishers.

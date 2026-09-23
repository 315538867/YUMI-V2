workspace "YUMI V2 Target Architecture" "Target-state architecture for the YUMI V2 order fulfillment system" {
    model {
        admin = person "Administrator" "Creates orders, schedules production, verifies work, ships goods, records payments, and handles after-sales."

        yumi = softwareSystem "YUMI V2" "Cloud-hosted order fulfillment system" {
            web = container "Shared React Web App" "Browser and Electron renderer business UI" "React + TypeScript"
            electron = container "Electron Desktop Shell" "Desktop window, printing, file selection, and OS integration only" "Electron"
            api = container "Modular Monolith API" "Authoritative business rules, module boundaries, migrations, and transactions" "Java 21 + Spring Boot 3.5.x + Spring Modulith"
            db = container "Business Database" "Orders, immutable facts, snapshots, ledgers, Flyway history, and audit records" "MySQL 8.x"
            files = container "File Storage" "Product images and generated files" "Object/file storage adapter"

            web -> api "Calls business API" "HTTPS/JSON"
            electron -> web "Hosts shared renderer"
            electron -> api "Uses same business API" "HTTPS/JSON"
            api -> db "Reads and writes transactions; applies Flyway migrations" "JPA/SQL"
            api -> files "Stores and reads files" "Storage API"
        }

        admin -> yumi.web "Uses in browser"
        admin -> yumi.electron "Uses desktop client"
    }

    views {
        systemContext yumi "SystemContext" {
            include *
            autolayout lr
        }

        container yumi "Containers" {
            include *
            autolayout lr
        }

        styles {
            element "Person" {
                shape person
                background #08427b
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
        }
    }
}

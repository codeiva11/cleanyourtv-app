plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:spec"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.apksig)
}



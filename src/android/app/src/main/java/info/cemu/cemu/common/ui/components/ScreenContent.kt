@file:OptIn(ExperimentalMaterial3Api::class)

package info.cemu.cemu.common.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.common.ui.wiiu.WiiUBackground
import info.cemu.cemu.common.ui.wiiu.WiiUBar
import info.cemu.cemu.common.ui.wiiu.WiiUColors
import info.cemu.cemu.common.ui.wiiu.WiiUTheme

@Composable
fun ScreenContent(
    appBarText: String,
    snackbarHost: @Composable () -> Unit = {},
    navigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    contentModifier: Modifier = DefaultContentModifier,
    contentVerticalArrangement: Arrangement.Vertical = DefaultContentArrangement,
    contentHorizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenContentGeneric(
        appBarTitle = { DefaultAppBarTitle(appBarText) },
        snackbarHost = snackbarHost,
        navigateBack = navigateBack,
        actions = actions,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = contentVerticalArrangement,
                horizontalAlignment = contentHorizontalAlignment,
                modifier = contentModifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

// wide screens keep the settings in a readable column in the middle, like the Wii U settings
private val ContentMaxWidth = 900.dp
private val DefaultContentModifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
private val DefaultContentArrangement = Arrangement.spacedBy(10.dp)

@Composable
fun DefaultAppBarTitle(appBarText: String) {
    Text(
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        text = appBarText,
        color = WiiUColors.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun ScreenContentLazy(
    appBarText: String,
    navigateBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    contentModifier: Modifier = DefaultContentModifier,
    contentVerticalArrangement: Arrangement.Vertical = DefaultContentArrangement,
    content: LazyListScope.() -> Unit,
) {
    ScreenContentGeneric(
        snackbarHost = snackbarHost,
        appBarTitle = { DefaultAppBarTitle(appBarText) },
        navigateBack = navigateBack,
        actions = actions,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                verticalArrangement = contentVerticalArrangement,
                modifier = contentModifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

@Composable
fun ScreenContentLazy(
    appBarTitle: @Composable () -> Unit,
    navigateBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    contentModifier: Modifier = DefaultContentModifier,
    contentVerticalArrangement: Arrangement.Vertical = DefaultContentArrangement,
    content: LazyListScope.() -> Unit,
) {
    ScreenContentGeneric(
        snackbarHost = snackbarHost,
        appBarTitle = appBarTitle,
        navigateBack = navigateBack,
        actions = actions,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                verticalArrangement = contentVerticalArrangement,
                modifier = contentModifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ScreenContentGeneric(
    navigateBack: () -> Unit,
    appBarTitle: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    WiiUTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            WiiUBackground()
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                snackbarHost = snackbarHost,
                topBar = {
                    WiiUBar(top = true) {
                        TopAppBar(
                            actions = actions,
                            title = appBarTitle,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                                titleContentColor = WiiUColors.Text,
                                navigationIconContentColor = WiiUColors.TextSecondary,
                                actionIconContentColor = WiiUColors.TextSecondary,
                            ),
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navigateBack() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = tr("Back"),
                                    )
                                }
                            },
                        )
                    }
                },
            ) { scaffoldPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                ) {
                    content()
                }
            }
        }
    }
}

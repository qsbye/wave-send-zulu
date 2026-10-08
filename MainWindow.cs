using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AirScanQR
{
    public class MainWindow : Form
    {
        private readonly HtmlView standardView;
        private readonly HtmlView fountainView;
        private readonly HtmlView decimenSenderView;
        private readonly HtmlView decimenReceiverView;
        private readonly Panel bar;
        private readonly Panel host;
        private readonly Button standardBtn;
        private readonly Button fountainBtn;
        private readonly Button decimenSenderBtn;
        private readonly Button decimenReceiverBtn;
        private readonly Button refreshBtn;
        private readonly Button downloadBtn;
        private readonly Label statusLabel;

        private HtmlView current;
        private bool switching;
        private readonly bool selfTest;

        private static readonly Color BarBg = Color.FromArgb(245, 246, 248);
        private static readonly Color Border = Color.FromArgb(222, 226, 230);
        private static readonly Color Accent = Color.FromArgb(22, 101, 216);
        private static readonly Color FgDim = Color.FromArgb(120, 128, 138);

        public MainWindow(bool selfTest = false)
        {
            this.selfTest = selfTest;
            Text = "AirScan-QR 跨网传输助手";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); }
            catch { Icon = SystemIcons.Application; }
            StartPosition = FormStartPosition.CenterScreen;
            Size = new Size(1180, 800);
            MinimumSize = new Size(880, 560);
            BackColor = Color.White;

            standardView = new HtmlView("AirScanQR.Assets.airscan-standard.html", "airscan-standard.html");
            fountainView = new HtmlView("AirScanQR.Assets.airscan-fountain.html", "airscan-fountain.html");
            decimenSenderView = new HtmlView("AirScanQR.Assets.decimen-v0.5.3-sender.html", "decimen-v0.5.3-sender.html");
            decimenReceiverView = new HtmlView("AirScanQR.Assets.decimen-v0.5.3-receiver.html", "decimen-v0.5.3-receiver.html");

            host = new Panel { Dock = DockStyle.Fill, BackColor = Color.White };
            host.Controls.Add(decimenReceiverView);
            host.Controls.Add(decimenSenderView);
            host.Controls.Add(fountainView);
            host.Controls.Add(standardView);

            bar = new Panel { Dock = DockStyle.Top, Height = 46, BackColor = BarBg };
            bar.Paint += (s, e) =>
            {
                using var pen = new Pen(Border);
                e.Graphics.DrawLine(pen, 0, bar.Height - 1, bar.Width - 1, bar.Height - 1);
            };

            var title = new Label
            {
                Text = "AirScan-QR",
                AutoSize = true,
                Font = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold),
                ForeColor = Color.FromArgb(30, 34, 40),
                Margin = new Padding(12, 0, 0, 0),
                Anchor = AnchorStyles.Left,
            };
            title.Location = new Point(12, 13);

            standardBtn = MakeTab("跨网传输助手", new Point(130, 8));
            standardBtn.Click += async (s, e) => await SwitchAsync(standardView);

            fountainBtn = MakeTab("喷泉码版", new Point(278, 8));
            fountainBtn.Click += async (s, e) => await SwitchAsync(fountainView);

            decimenSenderBtn = MakeTab("Decimen 发送端", new Point(426, 8));
            decimenSenderBtn.Width = 150;
            decimenSenderBtn.Click += async (s, e) => await SwitchAsync(decimenSenderView);

            decimenReceiverBtn = MakeTab("Decimen 接收端", new Point(584, 8));
            decimenReceiverBtn.Width = 150;
            decimenReceiverBtn.Click += async (s, e) => await SwitchAsync(decimenReceiverView);

            refreshBtn = MakeAction("刷新", new Point(0, 10), 74);
            refreshBtn.Click += (s, e) => current?.Reload();

            downloadBtn = MakeAction("下载目录", new Point(0, 10), 92);
            downloadBtn.Click += (s, e) => OpenDownloads();

            statusLabel = new Label
            {
                Text = "离线模式 · 数据不离开本机",
                AutoSize = true,
                Font = new Font("Microsoft YaHei UI", 9f),
                ForeColor = FgDim,
                Anchor = AnchorStyles.Right,
                Margin = new Padding(0, 0, 12, 0),
            };

            bar.Controls.Add(title);
            bar.Controls.Add(standardBtn);
            bar.Controls.Add(fountainBtn);
            bar.Controls.Add(decimenSenderBtn);
            bar.Controls.Add(decimenReceiverBtn);
            bar.Controls.Add(statusLabel);
            bar.Controls.Add(downloadBtn);
            bar.Controls.Add(refreshBtn);
            bar.Resize += (s, e) => LayoutBar();

            Controls.Add(host);
            Controls.Add(bar);

            Load += async (s, e) =>
            {
                await SwitchAsync(standardView);
                if (selfTest) await RunSelfTestAsync();
            };
        }

        private async Task RunSelfTestAsync()
        {
            const string probe = "JSON.stringify({title:document.title,url:location.href,chars:(document.body?document.body.innerText.length:0)})";
            var log = new System.Text.StringBuilder();
            try
            {
                await Task.Delay(2500);
                log.AppendLine("standard\t" + await standardView.EvalAsync(probe));
                await SwitchAsync(fountainView);
                await Task.Delay(2500);
                log.AppendLine("fountain\t" + await fountainView.EvalAsync(probe));
            }
            catch (Exception ex)
            {
                log.AppendLine("error\t" + ex.Message);
            }
            finally
            {
                try
                {
                    File.WriteAllText(
                        Path.Combine(Path.GetTempPath(), "airscan-selftest.txt"),
                        log.ToString());
                }
                catch { }
                Close();
            }
        }

        private static Button MakeTab(string text, Point location) => new Button
        {
            Text = text,
            Location = location,
            Size = new Size(138, 30),
            FlatStyle = FlatStyle.Flat,
            FlatAppearance = { BorderSize = 1, BorderColor = Border, MouseOverBackColor = Color.FromArgb(233, 237, 242), MouseDownBackColor = Color.FromArgb(222, 228, 236) },
            Font = new Font("Microsoft YaHei UI", 9f),
            ForeColor = Color.FromArgb(60, 66, 76),
            Cursor = Cursors.Hand,
        };

        private static Button MakeAction(string text, Point location, int width) => new Button
        {
            Text = text,
            Location = location,
            Size = new Size(width, 30),
            FlatStyle = FlatStyle.Flat,
            FlatAppearance = { BorderSize = 1, BorderColor = Border, MouseOverBackColor = Color.FromArgb(233, 237, 242), MouseDownBackColor = Color.FromArgb(222, 228, 236) },
            Font = new Font("Microsoft YaHei UI", 9f),
            ForeColor = Color.FromArgb(60, 66, 76),
            Cursor = Cursors.Hand,
        };

        private void LayoutBar()
        {
            statusLabel.Location = new Point(bar.Width - statusLabel.Width - 16, 15);
            downloadBtn.Location = new Point(statusLabel.Left - downloadBtn.Width - 8, 8);
            refreshBtn.Location = new Point(downloadBtn.Left - refreshBtn.Width - 8, 8);
        }

        private void UpdateTabs()
        {
            SetTabState(standardBtn, current == standardView);
            SetTabState(fountainBtn, current == fountainView);
            SetTabState(decimenSenderBtn, current == decimenSenderView);
            SetTabState(decimenReceiverBtn, current == decimenReceiverView);
        }

        private static void SetTabState(Button btn, bool on)
        {
            btn.BackColor = on ? Color.White : BarBg;
            btn.ForeColor = on ? Accent : Color.FromArgb(60, 66, 76);
            btn.FlatAppearance.BorderColor = on ? Accent : Border;
        }

        private async Task SwitchAsync(HtmlView target)
        {
            if (switching || current == target) return;
            switching = true;
            SetStatus("正在初始化 WebView2 …");

            var ok = await target.EnsureReadyAsync();
            if (!ok)
            {
                SetStatus("初始化失败");
                MessageBox.Show(
                    "WebView2 初始化失败：" + target.InitError +
                    "\n\n请确认已安装 Microsoft Edge WebView2 运行时。",
                    Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
                switching = false;
                return;
            }

            if (current != null) current.Visible = false;
            target.Visible = true;
            target.BringToFront();
            current = target;
            UpdateTabs();
            SetStatus("离线模式 · 数据不离开本机");
            switching = false;
        }

        private void SetStatus(string text)
        {
            statusLabel.Text = text;
            LayoutBar();
        }

        private void OpenDownloads()
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            try
            {
                Directory.CreateDirectory(dir);
                var focus = current?.LastDownloadPath;
                if (!string.IsNullOrEmpty(focus) && File.Exists(focus))
                    Process.Start("explorer.exe", "/select,\"" + focus + "\"");
                else
                    Process.Start("explorer.exe", "\"" + dir + "\"");
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
    }
}

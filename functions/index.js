const functions = require('firebase-functions');
const nodemailer = require('nodemailer');

// メール送信用のGmailトランスポーターを設定
const transporter = nodemailer.createTransport({
  host: 'smtp.gmail.com',
  port: 587,
  secure: false,  // TLS
  auth: {
    user: 'tbr.kurayama@gmail.com',
    pass: 'kikstdyellxzhcox'
  },
  tls: {
    rejectUnauthorized: false
  }
});

exports.sendStartNotification = functions.database
  .ref('/measurement_notifications/{sessionId}')
  .onCreate(async (snapshot, context) => {
    try {
      const sessionData = snapshot.val();
      console.log('Received notification data:', sessionData); // デバッグログ追加

      const mailOptions = {
        from: '"Accelerometer App" <tbr.kurayama@gmail.com>',
        to: 't-kurayama@uekusa.ac.jp',
        subject: '計測開始通知',
        text: `
加速度センサーの計測が開始されました

開始時刻: ${sessionData.startTime}
セッションID: ${sessionData.sessionId}
サンプリングレート: 120Hz
同期間隔: 5秒

このメールは自動送信されています。
`.trim()
      };

      console.log('Attempting to send email...'); // デバッグログ追加
      const info = await transporter.sendMail(mailOptions);
      console.log('Email sent successfully:', info); // 詳細なログ
      return null;

    } catch (error) {
      console.error('Error sending email:', error);
      console.error('Error details:', JSON.stringify(error)); // エラーの詳細をログに出力
      throw new functions.https.HttpsError('internal', `Error sending email: ${error.message}`);
    }
  });

// Health check function
exports.checkEmailService = functions.https.onRequest(async (req, res) => {
  try {
    await transporter.verify();
    res.json({ status: 'Email service is working' });
  } catch (error) {
    res.status(500).json({ 
      status: 'Email service error', 
      error: error.message 
    });
  }
});
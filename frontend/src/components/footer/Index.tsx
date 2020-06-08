import * as React from 'react';
import './Footer.less';
import { CopyrightOutlined } from '@ant-design/icons';

const Footer = () => (
  <div className="footer">
    <div className="copyright">
      <CopyrightOutlined />
      <div className="text">Copyright</div>
    </div>
  </div>
);

export default Footer;
